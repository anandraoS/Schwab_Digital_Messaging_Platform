import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';
import type { RecipientPreferences } from '../../core/models';
import { server } from '../../test/server';
import { json, page, renderWithProviders, seedSession } from '../../test/utils';
import { PreferencesPage, toForm, toUpdateRequest, validatePrefs } from './PreferencesPage';

const recipient = { id: 'rcp-040', externalRef: 'CUST-040', displayName: 'Ada Lovelace', maskedEmail: 'a***@example.test', maskedPhone: null, locale: 'en-US' };
const prefs = (over: Partial<RecipientPreferences> = {}): RecipientPreferences => ({
  recipientId: 'rcp-040', marketingOptIn: false, emailEnabled: true, smsEnabled: true, pushEnabled: false,
  quietStart: null, quietEnd: null, timeZone: 'Europe/Berlin', fallbackOrder: ['EMAIL', 'SMS'], version: 3, ...over,
});

describe('preferences helpers', () => {
  it('maps backend preferences to the form (null quiet hours become blank, channel array to CSV)', () => {
    expect(toForm(prefs())).toEqual({ marketingOptIn: false, emailEnabled: true, smsEnabled: true, pushEnabled: false, quietStart: '', quietEnd: '', fallbackOrder: 'EMAIL,SMS' });
  });

  it('builds the PUT body: blank quiet hours -> null, fallbackOrder -> channel array, loaded version, time zone kept', () => {
    const body = toUpdateRequest({ ...toForm(prefs()), fallbackOrder: 'SMS, EMAIL' }, prefs());
    expect(body).toEqual({
      marketingOptIn: false, emailEnabled: true, smsEnabled: true, pushEnabled: false, quietStart: null, quietEnd: null,
      timeZone: 'Europe/Berlin', fallbackOrder: ['SMS', 'EMAIL'], version: 3,
    });
    expect(Object.keys(body)).not.toContain('maskedEmail');
  });

  it('keeps explicit quiet hours and a null version for a recipient without stored preferences', () => {
    const body = toUpdateRequest({ ...toForm(prefs()), quietStart: '22:00', quietEnd: '07:30' }, prefs({ version: null, timeZone: null }));
    expect(body).toMatchObject({ quietStart: '22:00', quietEnd: '07:30', version: null, timeZone: null });
  });

  it('validates quiet hours, pairing and fallback order', () => {
    const f = toForm(prefs());
    expect(validatePrefs(f)).toEqual({});
    expect(validatePrefs({ ...f, quietStart: '25:00', quietEnd: '08:00' }).quietStart).toBeDefined();
    expect(validatePrefs({ ...f, quietStart: '21:00' }).quietPair).toBeDefined();
    expect(validatePrefs({ ...f, fallbackOrder: 'SMS,SMS' }).fallbackOrder).toBeDefined();
  });
});

function mount(roles: Parameters<typeof seedSession>[0] = ['OPERATOR'], handlers: Parameters<typeof server.use> = []) {
  seedSession(roles);
  server.use(
    ...handlers, // overrides first: the first matching handler wins
    http.get('*/api/v1/recipients', () => json(page([recipient]))),
    http.get('*/api/v1/recipients/rcp-040/preferences', () => json(prefs())),
  );
  return renderWithProviders(<PreferencesPage />, { route: '/preferences' });
}

async function openEditor() {
  await userEvent.type(screen.getByLabelText('Search recipients'), 'CUST-040');
  await userEvent.click(screen.getByRole('button', { name: 'Search' }));
  await userEvent.click(await screen.findByRole('button', { name: 'Edit preferences' }));
  await screen.findByRole('form', { name: 'Preferences form' });
}

describe('preferences page', () => {
  it('searches recipients and shows only masked addresses', async () => {
    mount();
    expect(screen.getByText('Search to find a recipient.')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Search recipients'), 'CUST-040');
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
    const table = await screen.findByRole('table', { name: 'Recipients' });
    expect(table).toHaveTextContent('a***@example.test');
    expect(table).toHaveTextContent('CUST-040');
  });

  it('loads the preferences into the form and shows the loaded version', async () => {
    mount();
    await openEditor();
    expect(screen.getByLabelText('E-mail enabled')).toBeChecked();
    expect(screen.getByLabelText('Push enabled')).not.toBeChecked();
    expect(screen.getByLabelText('Fallback order')).toHaveValue('EMAIL,SMS');
    expect(screen.getByLabelText(/Quiet hours start/)).toHaveValue('');
    expect(screen.getByText(/Loaded version: 3/)).toBeInTheDocument();
  });

  it('saves edits with the loaded version, null quiet hours and a channel-array fallback order, then shows Saved', async () => {
    let sent: unknown;
    mount(['OPERATOR'], [http.put('*/api/v1/recipients/rcp-040/preferences', async ({ request }) => { sent = await request.json(); return json(prefs({ fallbackOrder: ['SMS', 'EMAIL'], version: 4 })); })]);
    await openEditor();
    const fb = screen.getByLabelText('Fallback order');
    await userEvent.clear(fb);
    await userEvent.type(fb, 'SMS,EMAIL');
    await userEvent.click(screen.getByLabelText('Marketing opt-in'));
    await userEvent.click(screen.getByRole('button', { name: 'Save preferences' }));
    expect(await screen.findByText('Saved.')).toBeInTheDocument();
    expect(sent).toEqual({ marketingOptIn: true, emailEnabled: true, smsEnabled: true, pushEnabled: false, quietStart: null, quietEnd: null, timeZone: 'Europe/Berlin', fallbackOrder: ['SMS', 'EMAIL'], version: 3 });
    expect(screen.getByText(/Loaded version: 4/)).toBeInTheDocument();
  });

  it('does not save invalid input and shows inline errors', async () => {
    let calls = 0;
    mount(['OPERATOR'], [http.put('*/api/v1/recipients/rcp-040/preferences', () => { calls += 1; return json(prefs()); })]);
    await openEditor();
    await userEvent.type(screen.getByLabelText(/Quiet hours start/), '9pm');
    await userEvent.click(screen.getByRole('button', { name: 'Save preferences' }));
    expect(await screen.findByText('Use 24h HH:mm or leave blank')).toBeInTheDocument();
    expect(screen.getByText(/Set both quiet-hours ends/)).toBeInTheDocument();
    expect(calls).toBe(0);
  });

  it('optimistic-lock conflict: shows the conflict panel, then "Reload latest and re-apply my edits" keeps my edit on the fresh record', async () => {
    let getCount = 0;
    const puts: { version: number | null; marketingOptIn: boolean }[] = [];
    seedSession(['OPERATOR']);
    server.use(
      http.get('*/api/v1/recipients', () => json(page([recipient]))),
      http.get('*/api/v1/recipients/rcp-040/preferences', () => { getCount += 1; return json(getCount === 1 ? prefs() : prefs({ version: 7, smsEnabled: false })); }),
      http.put('*/api/v1/recipients/rcp-040/preferences', async ({ request }) => {
        const b = (await request.json()) as { version: number | null; marketingOptIn: boolean };
        puts.push(b);
        return b.version === 3 ? json({ code: 'CONFLICT_OPTIMISTIC_LOCK', message: 'stale' }, { status: 409 }) : json(prefs({ version: 8, smsEnabled: false, marketingOptIn: b.marketingOptIn }));
      }),
    );
    renderWithProviders(<PreferencesPage />, { route: '/preferences' });
    await openEditor();
    await userEvent.click(screen.getByLabelText('Marketing opt-in'));
    await userEvent.click(screen.getByRole('button', { name: 'Save preferences' }));
    const conflict = await screen.findByText('Edit conflict.');
    expect(conflict.parentElement).toHaveTextContent('Someone else changed this record first');
    await userEvent.click(screen.getByRole('button', { name: 'Reload latest and re-apply my edits' }));
    await waitFor(() => expect(screen.getByText(/Loaded version: 7/)).toBeInTheDocument());
    expect(screen.queryByText('Edit conflict.')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Marketing opt-in')).toBeChecked(); // my edit survived
    expect(screen.getByLabelText('SMS enabled')).not.toBeChecked(); // the other user's change was picked up
    await userEvent.click(screen.getByRole('button', { name: 'Save preferences' }));
    expect(await screen.findByText('Saved.')).toBeInTheDocument();
    expect(puts.map((p) => p.version)).toEqual([3, 7]);
  });

  it('optimistic-lock conflict: "Reload latest (discard my edits)" drops my edit', async () => {
    let getCount = 0;
    seedSession(['OPERATOR']);
    server.use(
      http.get('*/api/v1/recipients', () => json(page([recipient]))),
      http.get('*/api/v1/recipients/rcp-040/preferences', () => { getCount += 1; return json(getCount === 1 ? prefs() : prefs({ version: 9 })); }),
      http.put('*/api/v1/recipients/rcp-040/preferences', () => json({ code: 'CONFLICT_OPTIMISTIC_LOCK', message: 'stale' }, { status: 409 })),
    );
    renderWithProviders(<PreferencesPage />, { route: '/preferences' });
    await openEditor();
    await userEvent.click(screen.getByLabelText('Marketing opt-in'));
    await userEvent.click(screen.getByRole('button', { name: 'Save preferences' }));
    await screen.findByText('Edit conflict.');
    await userEvent.click(screen.getByRole('button', { name: 'Reload latest (discard my edits)' }));
    await waitFor(() => expect(screen.getByText(/Loaded version: 9/)).toBeInTheDocument());
    expect(screen.getByLabelText('Marketing opt-in')).not.toBeChecked();
  });

  it('other server errors (not conflicts) show a normal error banner', async () => {
    mount(['OPERATOR'], [http.put('*/api/v1/recipients/rcp-040/preferences', () => json({ code: 'RECIPIENT_NOT_FOUND', message: 'x' }, { status: 404 }))]);
    await openEditor();
    await userEvent.click(screen.getByRole('button', { name: 'Save preferences' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('recipient does not exist');
    expect(screen.queryByText('Edit conflict.')).not.toBeInTheDocument();
  });

  it('senders can view but not edit (fields disabled, no save button)', async () => {
    mount(['SENDER']);
    await openEditor();
    expect(screen.getByLabelText('Marketing opt-in')).toBeDisabled();
    expect(screen.getByLabelText('Fallback order')).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Save preferences' })).not.toBeInTheDocument();
    expect(screen.getByText(/can view preferences but not edit them/)).toBeInTheDocument();
  });

  it('a recipient without a stored row shows defaults and sends a null version', async () => {
    let sent: { version: number | null } | undefined;
    mount(['OPERATOR'], [
      http.get('*/api/v1/recipients/rcp-040/preferences', () => json(prefs({ version: null, fallbackOrder: [] }))),
      http.put('*/api/v1/recipients/rcp-040/preferences', async ({ request }) => { sent = (await request.json()) as { version: number | null }; return json(prefs({ version: 0 })); }),
    ]);
    await openEditor();
    expect(screen.getByText(/Loaded version: none \(defaults\)/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Save preferences' }));
    await screen.findByText('Saved.');
    expect(sent?.version).toBeNull();
  });
});
