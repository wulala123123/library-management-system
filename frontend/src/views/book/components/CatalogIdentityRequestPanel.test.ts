import { mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import Panel from './CatalogIdentityRequestPanel.vue'
import { useCatalogIdentitySession } from './catalogIdentitySession'
import { identityInput, identityRequest } from './catalogIdentityFixtures'
const wrappers: ReturnType<typeof mount>[] = []
const stubs = { ElAlert: { props: ['title'], template: '<p>{{ title }}</p>' }, ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' } }
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.useRealTimers() })
it('blocks confirmation when an alias preview does not include matching before/after evidence', () => {
  const input = identityInput(); input.changes = { publisher: null, themes: null, aliases: [{ kind: 'PUBLISHER', ownerId: 8, action: 'CLEAR', name: '原别名' }], categoryDimensions: [] }; const session = useCatalogIdentitySession(2, () => true); session.input.value = input; const view = identityRequest(input); view.preview.book.aliasChanges = []; session.view.value = view
  const wrapper = mount(Panel, { props: { session }, global: { stubs } }); wrappers.push(wrapper); expect(wrapper.text()).toContain('别名预览证据不完整'); expect(wrapper.findAll('button').find(button => button.text() === '确认身份变更')!.attributes('disabled')).toBeDefined()
})
it('disables an open preview as soon as its expiry passes', async () => {
  vi.useFakeTimers(); const input = identityInput(), session = useCatalogIdentitySession(2, () => true); session.input.value = input; const view = identityRequest(input); view.expiresAt = new Date(Date.now() + 500).toISOString(); session.view.value = view
  const wrapper = mount(Panel, { props: { session }, global: { stubs } }); wrappers.push(wrapper); expect(wrapper.findAll('button').find(button => button.text() === '确认身份变更')!.attributes('disabled')).toBeUndefined(); await vi.advanceTimersByTimeAsync(1000); expect(wrapper.findAll('button').find(button => button.text() === '确认身份变更')!.attributes('disabled')).toBeDefined()
})
