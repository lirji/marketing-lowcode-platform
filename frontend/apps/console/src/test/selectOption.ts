import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

export async function selectOption(trigger: HTMLElement, value: string) {
  await userEvent.click(trigger)
  const list = await screen.findByRole('listbox')
  const option = within(list).getAllByRole('option').find((item) => item.getAttribute('data-value') === value)
  if (!option) throw new Error(`Missing option: ${value}`)
  await userEvent.click(option)
}
