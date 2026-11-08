import userEvent from "@testing-library/user-event";
import fetchMock from "fetch-mock";

import {
  setupCollectionItemsEndpoint,
  setupPropertiesEndpoints,
  setupRemoteSyncEndpoints,
  setupSettingsEndpoints,
} from "__support__/server-mocks";
import { mockSettings } from "__support__/settings";
import { createMockState } from "__support__/state";
import { renderWithProviders, screen, waitFor } from "__support__/ui";
import type {
  CollectionItem,
  RemoteSyncTaskId,
  RemoteSyncType,
} from "metabase-types/api";
import {
  createMockCollectionItem,
  createMockSettingDefinition,
  createMockSettings,
  createMockUser,
} from "metabase-types/api/mocks";

import { SettingsForm } from "./SettingsForm";

const LIBRARY = createMockCollectionItem({
  id: 10,
  name: "Library",
  model: "collection",
  type: "library",
});
const MARKETING = createMockCollectionItem({
  id: 20,
  name: "Marketing",
  model: "collection",
});

type SetupOpts = {
  isEnabled?: boolean;
  url?: string;
  syncType?: RemoteSyncType;
  collections?: CollectionItem[];
  settingsTaskId?: RemoteSyncTaskId;
};

const setup = ({
  isEnabled = false,
  url = "",
  syncType = "read-write",
  collections = [LIBRARY, MARKETING],
  settingsTaskId,
}: SetupOpts = {}) => {
  const settings = createMockSettings({
    "remote-sync-enabled": isEnabled,
    "remote-sync-url": url,
    "remote-sync-type": syncType,
    "remote-sync-branch": isEnabled ? "main" : null,
  });
  const onSaved = jest.fn<void, []>();

  setupPropertiesEndpoints(settings);
  setupSettingsEndpoints(
    (
      [
        "remote-sync-url",
        "remote-sync-token",
        "remote-sync-type",
        "remote-sync-branch",
        "remote-sync-auto-import",
      ] as const
    ).map((key) => createMockSettingDefinition({ key, value: settings[key] })),
  );
  setupCollectionItemsEndpoint({
    collection: { id: "root" },
    collectionItems: collections,
  });
  setupRemoteSyncEndpoints({ settingsTaskId });

  renderWithProviders(
    <SettingsForm triggeredFrom="admin-settings" onSaved={onSaved} />,
    {
      storeInitialState: createMockState({
        currentUser: createMockUser({ is_superuser: true }),
        settings: mockSettings(settings),
      }),
    },
  );

  return { onSaved };
};

const getSettingsRequestBody = async () => {
  const [call] = fetchMock.callHistory.calls(
    "path:/api/ee/remote-sync/settings",
  );
  return call?.request?.json();
};

describe("SettingsForm", () => {
  it("sets up remote sync of Library and chosen collections", async () => {
    const { onSaved } = setup();

    await userEvent.type(
      await screen.findByLabelText("Repository URL"),
      "https://github.com/acme/metabase.git",
    );
    await userEvent.click(screen.getByRole("switch", { name: "Marketing" }));
    await userEvent.click(
      screen.getByRole("button", { name: "Set up remote sync" }),
    );

    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1));
    expect(await getSettingsRequestBody()).toEqual({
      "remote-sync-url": "https://github.com/acme/metabase.git",
      "remote-sync-type": "read-write",
      "remote-sync-branch": null,
      "remote-sync-auto-import": false,
      collections: { [LIBRARY.id]: true, [MARKETING.id]: true },
    });
  });

  it("pulls read-only instances once saved and shows pull progress", async () => {
    const { onSaved } = setup({ settingsTaskId: 1 });

    await userEvent.type(
      await screen.findByLabelText("Repository URL"),
      "https://github.com/acme/metabase.git",
    );
    await userEvent.click(screen.getByLabelText("Read-only"));

    expect(
      screen.queryByRole("switch", { name: "Marketing" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("switch", { name: "Pull changes automatically" }),
    ).toBeInTheDocument();

    await userEvent.click(
      screen.getByRole("button", { name: "Set up remote sync" }),
    );

    expect(await screen.findByText("Pulling changes")).toBeInTheDocument();
    expect(onSaved).not.toHaveBeenCalled();
    expect(await getSettingsRequestBody()).toMatchObject({
      "remote-sync-type": "read-only",
      collections: null,
    });
  });

  it("tests repository connection", async () => {
    setup();

    await userEvent.type(
      await screen.findByLabelText("Repository URL"),
      "https://github.com/acme/metabase.git",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Test connection" }),
    );

    await waitFor(() =>
      expect(
        fetchMock.callHistory.called(
          "path:/api/ee/remote-sync/test-connection",
        ),
      ).toBe(true),
    );
    const [call] = fetchMock.callHistory.calls(
      "path:/api/ee/remote-sync/test-connection",
    );
    expect(await call?.request?.json()).toEqual({
      "remote-sync-url": "https://github.com/acme/metabase.git",
    });
  });

  it("can turn remote sync off once it is set up", async () => {
    setup({ isEnabled: true, url: "https://github.com/acme/metabase.git" });

    expect(
      await screen.findByRole("button", { name: "Turn off remote sync" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Save changes" }),
    ).toBeInTheDocument();
  });
});
