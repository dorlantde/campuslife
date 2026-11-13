import { shallowRef } from 'vue'

// Use an in-page choice instead of a blocking browser-native confirm dialog.
export function createDiscardConfirmation() {
  const pending = shallowRef<{ message: string; resolve: (discard: boolean) => void } | null>(null)
  function ask(message: string): Promise<boolean> {
    if (pending.value) return Promise.resolve(false)
    return new Promise(resolve => { pending.value = { message, resolve } })
  }
  function settle(discard: boolean) {
    const choice = pending.value
    pending.value = null
    choice?.resolve(discard)
  }
  return { pending, ask, settle }
}
const confirmation = createDiscardConfirmation()
export const discardRequest = confirmation.pending
export const askDiscard = confirmation.ask
export const settleDiscard = confirmation.settle
