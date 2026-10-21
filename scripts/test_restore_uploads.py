"""Synthetic checks for archive boundaries and exact restoration metadata."""
from decimal import Decimal
import io
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

from restore_uploads import inventory, restore, capture, upload_context


class UploadRestoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='library-upload-metadata-')
        self.root = Path(self.temp.name).resolve()
        self.addCleanup(self.temp.cleanup)

    def archive(self, entries, name='source.tar.gz'):
        path = self.root/name
        with tarfile.open(path, 'w:gz', format=tarfile.PAX_FORMAT) as target:
            for item, raw in entries:
                target.addfile(item, io.BytesIO(raw) if item.isfile() else None)
        return path

    def directory(self, name='uploads', mtime='1.123456789'):
        item = tarfile.TarInfo(name); item.type = tarfile.DIRTYPE
        item.uid = 0; item.gid = 0; item.mode = 0o750; item.mtime = 1
        item.pax_headers = {'mtime': mtime}
        return item, b''

    def file(self, name="uploads/a ' $(do-not-run).txt", raw=b'\0\n\xff'):
        item = tarfile.TarInfo(name); item.size = len(raw)
        item.uid = 10001; item.gid = 101; item.mode = 0o640
        item.mtime = 0
        return item, raw

    def test_bytes_owner_modes_and_nanoseconds_retained(self):
        items = inventory(self.archive([self.directory(), self.file()]))
        self.assertEqual(items['uploads']['mtime'], Decimal('1.123456789'))
        self.assertEqual(items['uploads']['uid'], 0)
        value = items["uploads/a ' $(do-not-run).txt"]
        self.assertEqual((value['uid'], value['gid'], value['mode'], value['size']), (10001, 101, 0o640, 3))

    def test_rejects_escape_links_devices_and_duplicate_names(self):
        bad = []
        for name in ('/uploads/x', 'uploads/../escape', 'elsewhere/x', 'uploads\\x'):
            bad.append([self.directory(), self.file(name)])
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.FIFOTYPE, tarfile.CHRTYPE):
            item, _ = self.file('uploads/x'); item.type = kind; item.linkname = '/outside'
            bad.append([self.directory(), (item, b'')])
        bad.append([self.directory(), self.file('uploads/x'), self.file('uploads/./x')])
        for n, entries in enumerate(bad):
            with self.subTest(n=n), self.assertRaisesRegex(ValueError, 'UNSAFE_UPLOAD_ARCHIVE'):
                inventory(self.archive(entries, str(n)+'.tar.gz'))

    def test_missing_root_and_file_as_parent_rejected(self):
        with self.assertRaisesRegex(ValueError, 'UPLOAD_ROOT_REQUIRED'):
            inventory(self.archive([self.file()]))
        with self.assertRaisesRegex(ValueError, 'UPLOAD_PARENT_NOT_DIRECTORY'):
            inventory(self.archive([self.directory(), self.file('uploads/x'), self.file('uploads/x/y')]))

    def test_invalid_precision_rejected_without_executing_helper(self):
        path = self.archive([self.directory(mtime='1.1234567891')])
        with patch('restore_uploads.subprocess.run') as command:
            with self.assertRaisesRegex(ValueError, 'UNSUPPORTED_UPLOAD_TIME_PRECISION'):
                restore(path, ['docker', 'compose', '-p', 'isolated'])
            command.assert_not_called()

    def test_production_namespace_never_allowed(self):
        path = self.archive([self.directory()])
        with patch('restore_uploads.subprocess.run') as command:
            with self.assertRaisesRegex(ValueError, 'ISOLATED_PROJECT_REQUIRED'):
                restore(path, ['docker', 'compose', '-p', 'library'])
            command.assert_not_called()

    def test_creation_failure_never_copies_or_starts_apps(self):
        path = self.archive([self.directory(), self.file()])
        with patch('restore_uploads.subprocess.check_output', return_value=b''), patch('restore_uploads.subprocess.run', side_effect=subprocess.CalledProcessError(1, 'create')) as command:
            with self.assertRaises(subprocess.CalledProcessError):
                restore(path, ['docker', 'compose', '-p', 'isolated'])
            self.assertEqual(command.call_count, 1)

    def test_ambiguous_or_injected_compose_options_rejected(self):
        path = self.archive([self.directory()])
        for options in (['-p'], ['-p', 'isolated', '-p', 'library'],
                        ['--project-name', 'library'], ['-plibrary'],
                        ['-p', 'isolated', 'up'], ['-p', '../library']):
            with self.subTest(options=options), patch('restore_uploads.subprocess.run') as command:
                with self.assertRaises(ValueError):
                    restore(path, ['docker', 'compose', *options])
                command.assert_not_called()

    def test_stopped_creation_and_native_archive_copy(self):
        path = self.archive([self.directory(), self.file()])
        raw_archives = []
        def record(*args, **kwargs):
            if 'stdin' in kwargs:
                raw_archives.append(kwargs['stdin'].read())
        with patch('restore_uploads.subprocess.check_output', return_value=b''), patch('restore_uploads.upload_context', return_value=['docker','run','--network','none','--entrypoint','tar','image']), patch('restore_uploads.subprocess.run', side_effect=record) as command:
            restore(path, ['docker', 'compose', '-p', 'isolated'])
            self.assertEqual(command.call_count, 2)
            self.assertIn('--no-start', command.call_args_list[0].args[0])
            self.assertIn('--no-deps', command.call_args_list[0].args[0])
            self.assertIn('--same-owner', command.call_args_list[1].args[0])
            self.assertIn('--numeric-owner', command.call_args_list[1].args[0])
            self.assertIn('--delay-directory-restore', command.call_args_list[1].args[0])
            with tarfile.open(fileobj=io.BytesIO(raw_archives[0]), mode='r:gz') as raw:
                self.assertEqual(raw.getmember('uploads').pax_headers['mtime'], '1.123456789')
                self.assertEqual(raw.extractfile("uploads/a ' $(do-not-run).txt").read(), b'\0\n\xff')

    def test_existing_or_ambiguous_backend_rejected(self):
        path = self.archive([self.directory()])
        for responses in ([b'a'*64], [b'', b'a'*64+b'\n'+b'b'*64]):
            with self.subTest(responses=responses), patch('restore_uploads.subprocess.check_output', side_effect=responses), patch('restore_uploads.subprocess.run') as command:
                with self.assertRaises(ValueError):
                    restore(path, ['docker', 'compose', '-p', 'isolated'])
                self.assertLessEqual(command.call_count, 1)

    def test_helper_uses_only_owned_upload_volume_and_existing_mysql_image(self):
        import json
        backend={'Config':{'Labels':{'com.docker.compose.project':'isolated','com.docker.compose.service':'backend'}},'Mounts':[{'Destination':'/app/uploads','Type':'volume','Name':'isolated_uploads'}]}
        mysql={'Config':{'Labels':{'com.docker.compose.project':'isolated','com.docker.compose.service':'mysql'}},'Image':'sha256:'+'c'*64}
        with patch('restore_uploads.subprocess.check_output', side_effect=[b'a'*64,b'b'*64,json.dumps([backend,mysql]).encode()]):
            helper=upload_context(['docker','compose','-p','isolated'])
        self.assertIn('none',helper); self.assertIn('--read-only',helper)
        self.assertEqual(helper[helper.index('--mount')+1],'type=volume,src=isolated_uploads,dst=/app/uploads')
        backend['Mounts'][0]['Name']='library_uploads'
        with patch('restore_uploads.subprocess.check_output', side_effect=[b'a'*64,b'b'*64,json.dumps([backend,mysql]).encode()]), self.assertRaisesRegex(ValueError,'EXACT_UPLOAD_VOLUME_REQUIRED'):
            upload_context(['docker','compose','-p','isolated'])


if __name__ == '__main__':
    unittest.main()
