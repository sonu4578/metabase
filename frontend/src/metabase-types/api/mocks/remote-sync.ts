import type {
  RemoteSyncEntity,
  RemoteSyncEntityCollection,
  RemoteSyncEntityCollectionAncestor,
  RemoteSyncTask,
} from "../remote-sync";

export const createMockRemoteSyncEntity = (
  opts?: Partial<RemoteSyncEntity>,
): RemoteSyncEntity => ({
  id: 1,
  name: "My Question",
  model: "card",
  sync_status: "update",
  ...opts,
});

export const createMockRemoteSyncEntityCollectionAncestor = (
  opts?: Partial<RemoteSyncEntityCollectionAncestor>,
): RemoteSyncEntityCollectionAncestor => ({
  id: 1,
  name: "Parent collection",
  type: null,
  personal_owner_id: null,
  ...opts,
});

export const createMockRemoteSyncEntityCollection = (
  opts?: Partial<RemoteSyncEntityCollection>,
): RemoteSyncEntityCollection => ({
  id: 2,
  name: "Collection",
  type: null,
  effective_ancestors: [],
  ...opts,
});

export const createMockRemoteSyncTask = (
  opts?: Partial<RemoteSyncTask>,
): RemoteSyncTask => ({
  id: 1,
  sync_task_type: "import",
  status: "running",
  progress: 0,
  started_at: "2026-01-01T00:00:00Z",
  ...opts,
});
