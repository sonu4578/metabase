import { t } from "ttag";

export const getPullTitle = (branch: string, targetBranch: string) =>
  targetBranch === branch
    ? t`Pulling changes`
    : t`Switching to ${targetBranch}`;
