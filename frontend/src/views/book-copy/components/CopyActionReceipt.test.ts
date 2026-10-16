import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import ElementPlus from 'element-plus'
import CopyActionReceipt from './CopyActionReceipt.vue'
import { actionReceipt, actionReceiptV2, actionRequest, actionViewV2 } from './copyActionFixtures'
it('renders an original V1 receipt without fabricating new placement/source fields',()=>{
  const receipt=actionReceipt(),before=JSON.stringify(receipt),wrapper=mount(CopyActionReceipt,{props:{receipt},global:{plugins:[ElementPlus]}})
  expect(wrapper.text()).toContain('记录版本 V1');expect(wrapper.text()).toContain('位置（V1 原记录）');expect(wrapper.text()).not.toContain('原位置来源');expect(wrapper.text()).not.toContain('登记地址 #');expect(JSON.stringify(receipt)).toBe(before);wrapper.unmount()
})
it('renders registered V2 snapshots and literal strings from the receipt, without consulting live addresses',()=>{
  const request=actionRequest(),view=actionViewV2({...request,schemaVersion:2,copies:[{copyId:7,expectedVersion:3,targetLocation:{kind:'REGISTERED',locationId:12,expectedAddressVersion:{location:3,storey:4,bookshelf:5}}}]}),wrapper=mount(CopyActionReceipt,{props:{receipt:actionReceiptV2(view)},global:{plugins:[ElementPlus]}})
  expect(wrapper.text()).toContain('记录版本 V2');expect(wrapper.text()).toContain('登记地址 #12');expect(wrapper.text()).toContain('成功后来源 101');expect(wrapper.find('img').exists()).toBe(false);wrapper.unmount()
})
