package com.library;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstanceFactoryContext;
import org.junit.jupiter.api.extension.TestInstancePreConstructCallback;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** Owns uploads for one test class, including failures before Spring finishes creating its test instance. */
final class HttpTestUploads implements TestInstancePreConstructCallback {
    private Directory resource;

    @Override
    public void preConstructTestInstance(TestInstanceFactoryContext factory, ExtensionContext context) throws Exception {
        var store=context.getStore(ExtensionContext.Namespace.create(HttpTestUploads.class));
        resource=new Directory(Files.createTempDirectory("library-http-uploads-"));
        store.put(this,resource);
    }

    Path directory() {
        if(resource==null) throw new IllegalStateException("Upload directory must be registered before Spring starts");
        return resource.path;
    }

    private static final class Directory implements ExtensionContext.Store.CloseableResource {
        private final Path path;

        private Directory(Path path) {
            this.path=path;
        }

        @Override
        public void close() throws IOException {
            Files.walkFileTree(path,new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file,BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory,IOException failure) throws IOException {
                    if(failure!=null) throw failure;
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }
}
