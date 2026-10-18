import {afterEach,beforeEach,expect,it} from 'vitest'
import {createPinia,setActivePinia} from 'pinia'
import router from '@/router'
import {useUserStore} from '@/store/modules/user'
beforeEach(async()=>{localStorage.clear();setActivePinia(createPinia());await router.push('/login')})
afterEach(()=>localStorage.clear())
it.each(['LIBRARIAN','READER'])('refuses a manual finance URL for %s in the router',async role=>{const user=useUserStore();user.setToken('synthetic');user.setUserInfo({id:9,username:'synthetic',realName:'合成',role,phone:'',email:'',avatar:''});await router.push('/member-finance/9007199254740993');expect(router.currentRoute.value.path).toBe('/403')})
it('allows Admin finance route with a string ID and keeps anonymous access at login',async()=>{await router.push('/member-finance/1');expect(router.currentRoute.value.path).toBe('/login');const user=useUserStore();user.setToken('synthetic');user.setUserInfo({id:9,username:'synthetic',realName:'合成',role:'ADMIN',phone:'',email:'',avatar:''});await router.push('/member-finance/9007199254740993');expect(router.currentRoute.value.params.memberId).toBe('9007199254740993');expect(router.currentRoute.value.meta.roles).toEqual(['ADMIN'])})
