import { expect, it, vi } from 'vitest'
import { useLocationMaintenanceSession } from './locationMaintenanceSession'
import { structureEvent } from '@/components/locations/locationFixtures'
it('re-reads after timeout, preserves review lock and never automatically repeats a maintenance write', async () => {
  const event=structureEvent(), write=vi.fn().mockRejectedValue(new Error('timeout')), read=vi.fn().mockResolvedValue({current:event.after,events:[event]}), session=useLocationMaintenanceSession()
  expect(await session.submit(write,read)).toBe(false);expect(read).toHaveBeenCalledOnce();expect(session.needsReview.value).toBe(true);expect(session.readback.value?.events[0]?.id).toBe(18)
  await session.submit(write,read);expect(write).toHaveBeenCalledOnce();expect(session.reset()).toBe(false)
  expect(session.reviewed()).toBe(true);expect(session.needsReview.value).toBe(false)
})
it('holds after stale version or failed lookup, and accepts deletion only as readback without a retry', async () => {
  const session=useLocationMaintenanceSession(), write=vi.fn().mockRejectedValue({response:{status:409,data:{data:{reasonCode:'LOCATION_STALE'}}}}), read=vi.fn().mockRejectedValue(new Error('offline'))
  await session.submit(write,read);expect(session.reviewed()).toBe(false);expect(session.error.value).toContain('LOCATION_STALE')
  const event=structureEvent();read.mockResolvedValue({current:{...event.after,deleted:1},events:[event]});await session.recover();expect(session.reviewed()).toBe(false);expect(write).toHaveBeenCalledOnce()
})
