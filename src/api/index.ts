import { invoke, isTauri } from "@tauri-apps/api/core";
import { open, confirm } from "@tauri-apps/plugin-dialog";
export const desktop = isTauri();
export async function copyUrl(id: string, url: string): Promise<void> {
  if (desktop) await call("copy_url", { id });
  else await navigator.clipboard.writeText(url);
}
export async function confirmAction(message: string): Promise<boolean> {
  return desktop
    ? confirm(message, {
        title: "Hertix",
        kind: "warning",
        okLabel: "Continue",
        cancelLabel: "Cancel",
      })
    : window.confirm(message);
}
export async function call<T>(
  command: string,
  args?: Record<string, unknown>,
): Promise<T> {
  if (!desktop)
    throw new Error(
      "Open the Hertix desktop application to use the local media engine. Browser preview does not have desktop access.",
    );
  try {
    return await invoke<T>(command, args);
  } catch (error) {
    if (typeof error === "object" && error !== null && "message" in error)
      throw new Error(String(error.message));
    throw new Error(String(error));
  }
}
export async function choose(folder: boolean): Promise<string | null> {
  if (!desktop)
    throw new Error(
      "Native file selection is available in the Windows application.",
    );
  const selection = await open({
    directory: folder,
    multiple: false,
    ...(folder
      ? {}
      : {
          filters: [
            { name: "Executable or cookies file", extensions: ["exe", "txt"] },
          ],
        }),
  });
  return typeof selection === "string" ? selection : null;
}
