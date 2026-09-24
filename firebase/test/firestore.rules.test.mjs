// The mailbox's only server-side code is firestore.rules, so these tests are the only thing standing
// between a typo in it and a stranger writing into someone's inbox. Run against the local emulator:
//
//   cd firebase && npm install && npm test
import { after, before, beforeEach, describe, test } from 'node:test';
import { readFileSync } from 'node:fs';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  Bytes,
  Timestamp,
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  setDoc,
  updateDoc,
} from 'firebase/firestore';

const DAY = 24 * 60 * 60 * 1000;

let env;

const as = (uid) => env.authenticatedContext(uid).firestore();
const anonymous = () => env.unauthenticatedContext().firestore();
const sealed = (size = 3) => Bytes.fromUint8Array(new Uint8Array(size));
const inDays = (days) => Timestamp.fromMillis(Date.now() + days * DAY);

async function seed(path, data) {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), path), data);
  });
}

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-dhanmoney',
    firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8') },
  });
});

beforeEach(async () => {
  await env.clearFirestore();
});

after(async () => {
  await env.cleanup();
});

describe('public keys', () => {
  const keys = () => ({
    encKey: 'ZW5jcnlwdGlvbg',
    sigKey: 'c2lnbmluZw',
    fingerprint: '0123456789abcdef0123456789abcdef',
    updatedAt: Timestamp.now(),
  });

  test('anyone signed in can read one person, and nobody can list everyone', async () => {
    await seed('users/bob', keys());
    await assertSucceeds(getDoc(doc(as('alice'), 'users/bob')));
    await assertFails(getDoc(doc(anonymous(), 'users/bob')));
    await assertFails(getDocs(collection(as('alice'), 'users')));
  });

  test('only the owner publishes, and only keys', async () => {
    await assertSucceeds(setDoc(doc(as('bob'), 'users/bob'), keys()));
    await assertFails(setDoc(doc(as('mallory'), 'users/bob'), keys()));
    await assertFails(setDoc(doc(as('bob'), 'users/bob'), { ...keys(), email: 'bob@example.com' }));
    await assertFails(setDoc(doc(as('bob'), 'users/bob'), { ...keys(), fingerprint: 'short' }));
  });
});

describe('contacts', () => {
  test('are written and read by their owner alone', async () => {
    await assertSucceeds(setDoc(doc(as('bob'), 'users/bob/contacts/alice'), { addedAt: Timestamp.now() }));
    await assertFails(setDoc(doc(as('alice'), 'users/bob/contacts/alice'), { addedAt: Timestamp.now() }));
    await assertFails(getDocs(collection(as('alice'), 'users/bob/contacts')));
    await assertSucceeds(getDocs(collection(as('bob'), 'users/bob/contacts')));
  });
});

describe('invites', () => {
  test('are made by their owner and expire within a week', async () => {
    await assertSucceeds(setDoc(doc(as('alice'), 'invites/inv1'), { owner: 'alice', expiresAt: inDays(7) }));
    await assertFails(setDoc(doc(as('mallory'), 'invites/inv2'), { owner: 'alice', expiresAt: inDays(7) }));
    await assertFails(setDoc(doc(as('alice'), 'invites/inv3'), { owner: 'alice', expiresAt: inDays(30) }));
  });

  test('can only be read or withdrawn by their owner', async () => {
    await seed('invites/inv1', { owner: 'alice', expiresAt: inDays(1) });
    await assertSucceeds(getDoc(doc(as('alice'), 'invites/inv1')));
    await assertFails(getDoc(doc(as('bob'), 'invites/inv1')));
    await assertFails(deleteDoc(doc(as('bob'), 'invites/inv1')));
    await assertSucceeds(deleteDoc(doc(as('alice'), 'invites/inv1')));
  });
});

describe('inbox', () => {
  const message = (from, extra = {}) => ({ from, ciphertext: sealed(), ...extra });

  test('a stranger cannot write to it', async () => {
    await assertFails(setDoc(doc(as('mallory'), 'inbox/bob/messages/m1'), message('mallory')));
  });

  test('a contact can, but never as somebody else', async () => {
    await seed('users/bob/contacts/alice', { addedAt: Timestamp.now() });
    await assertSucceeds(setDoc(doc(as('alice'), 'inbox/bob/messages/m1'), message('alice')));
    await assertFails(setDoc(doc(as('mallory'), 'inbox/bob/messages/m2'), message('alice')));
    await assertFails(setDoc(doc(as('alice'), 'inbox/bob/messages/m3'), message('carol')));
  });

  test('carries nothing but a sealed message of bounded size', async () => {
    await seed('users/bob/contacts/alice', { addedAt: Timestamp.now() });
    await assertFails(setDoc(doc(as('alice'), 'inbox/bob/messages/m1'), message('alice', { amount: 300 })));
    await assertFails(setDoc(doc(as('alice'), 'inbox/bob/messages/m2'), { from: 'alice', ciphertext: 'plain text' }));
    await assertFails(setDoc(doc(as('alice'), 'inbox/bob/messages/m3'), { from: 'alice', ciphertext: sealed(262145) }));
  });

  test('an open invite lets its holder answer it, and nothing else does', async () => {
    await seed('invites/open', { owner: 'alice', expiresAt: inDays(1) });
    await seed('invites/carols', { owner: 'carol', expiresAt: inDays(1) });
    await seed('invites/expired', { owner: 'alice', expiresAt: inDays(-1) });

    await assertSucceeds(setDoc(doc(as('bob'), 'inbox/alice/messages/m1'), message('bob', { inviteId: 'open' })));
    // Someone else's invite is not a way into this inbox, and neither is an expired or missing one.
    await assertFails(setDoc(doc(as('bob'), 'inbox/alice/messages/m2'), message('bob', { inviteId: 'carols' })));
    await assertFails(setDoc(doc(as('bob'), 'inbox/alice/messages/m3'), message('bob', { inviteId: 'expired' })));
    await assertFails(setDoc(doc(as('bob'), 'inbox/alice/messages/m4'), message('bob', { inviteId: 'missing' })));
  });

  test('only its owner reads it, the owner or sender may delete, and nobody edits', async () => {
    await seed('inbox/bob/messages/m1', message('alice'));
    await assertSucceeds(getDocs(collection(as('bob'), 'inbox/bob/messages')));
    await assertFails(getDocs(collection(as('alice'), 'inbox/bob/messages')));
    await assertFails(getDoc(doc(as('alice'), 'inbox/bob/messages/m1')));
    await assertFails(updateDoc(doc(as('bob'), 'inbox/bob/messages/m1'), { from: 'bob' }));
    await assertFails(deleteDoc(doc(as('carol'), 'inbox/bob/messages/m1')));
    await assertSucceeds(deleteDoc(doc(as('alice'), 'inbox/bob/messages/m1')));

    await seed('inbox/bob/messages/m2', message('alice'));
    await assertSucceeds(deleteDoc(doc(as('bob'), 'inbox/bob/messages/m2')));
  });

  test('nobody writes into their own inbox', async () => {
    await seed('users/bob/contacts/bob', { addedAt: Timestamp.now() });
    await assertFails(setDoc(doc(as('bob'), 'inbox/bob/messages/m1'), message('bob')));
  });
});
