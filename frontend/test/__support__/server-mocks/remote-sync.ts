import fetchMock from "fetch-mock";

import type {
  ExportPreflightResponse,
  RemoteSyncEntity,
  RemoteSyncTask,
} from "metabase-types/api";

type SetupRemoteSyncEndpointsOpts = {
  branches?: string[];
  dirty?: RemoteSyncEntity[];
  hasRemoteChanges?: boolean;
  currentTask?: RemoteSyncTask | null;
  exportPreflight?: ExportPreflightResponse;
  settingsTaskId?: number;
};

const DEFAULT_EXPORT_PREFLIGHT: ExportPreflightResponse = {
  has_changes: false,
  clean: true,
  conflicts: [],
  summary: { added: 0, updated: 0, removed: 0 },
  force_push_casualties: { deleted: [], overwritten: [] },
  reason: null,
};

export const setupRemoteSyncEndpoints = ({
  branches = ["main", "develop"],
  dirty = [],
  hasRemoteChanges = false,
  currentTask = null,
  exportPreflight = DEFAULT_EXPORT_PREFLIGHT,
  settingsTaskId,
}: SetupRemoteSyncEndpointsOpts = {}) => {
  fetchMock.get("path:/api/ee/remote-sync/branches", { items: branches });
  fetchMock.get("path:/api/ee/remote-sync/dirty", { dirty });
  fetchMock.get("path:/api/ee/remote-sync/is-dirty", {
    is_dirty: dirty.length > 0,
  });
  fetchMock.get("path:/api/ee/remote-sync/has-remote-changes", {
    has_changes: hasRemoteChanges,
    remote_version: "remote",
    local_version: hasRemoteChanges ? "local" : "remote",
    cached: false,
  });
  fetchMock.get("path:/api/ee/remote-sync/current-task", currentTask ?? 204);
  fetchMock.get("path:/api/ee/remote-sync/export-preflight", exportPreflight);
  fetchMock.post("path:/api/ee/remote-sync/import", {
    status: "success",
    task_id: 1,
  });
  fetchMock.post("path:/api/ee/remote-sync/export", {
    message: "Export task started",
    task_id: 1,
  });
  fetchMock.post("path:/api/ee/remote-sync/stash", {
    status: "success",
    message: "Stashing",
    task_id: 1,
  });
  fetchMock.post("path:/api/ee/remote-sync/current-task/cancel", 200);
  fetchMock.put("path:/api/ee/remote-sync/settings", {
    success: true,
    ...(settingsTaskId != null && { task_id: settingsTaskId }),
  });
  fetchMock.post("path:/api/ee/remote-sync/test-connection", {
    status: "success",
  });
};
