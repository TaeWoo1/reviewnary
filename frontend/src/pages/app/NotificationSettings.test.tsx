// @vitest-environment jsdom
import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { NotificationSettings } from "./NotificationSettings";
import { readNoticeSettings } from "../../lib/workNotifications";

describe("PC 알림 설정", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("turning on asks the browser, and the choice is kept on this computer", async () => {
    const requestPermission = vi.fn().mockResolvedValue("granted");
    vi.stubGlobal("Notification", Object.assign(function () {}, { permission: "default", requestPermission }));
    render(<NotificationSettings />);
    expect(screen.getByText(/reviewnary가 이 컴퓨터에서 열려 있을 때만/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "알림 켜기" }));
    await waitFor(() => expect(readNoticeSettings().enabled).toBe(true));
    expect(requestPermission).toHaveBeenCalled();
    fireEvent.click(screen.getByRole("checkbox"));
    expect(readNoticeSettings().hidePreview).toBe(true);
    vi.unstubAllGlobals();
  });

  it("a browser that blocked notifications is said, and the button stays off", () => {
    vi.stubGlobal("Notification", Object.assign(function () {}, { permission: "denied", requestPermission: vi.fn() }));
    render(<NotificationSettings />);
    expect(screen.getByText(/알림이 막혀 있어요/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "알림 켜기" })).toBeDisabled();
    vi.unstubAllGlobals();
  });
});
