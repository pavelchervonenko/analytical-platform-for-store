import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { UsersPanel } from "./UsersPanel";
import {
  createAdminUser,
  getAdminUsers,
  updateAdminUser,
  type AdminUser
} from "./api";

vi.mock("../auth/AuthProvider", () => ({
  useAuth: () => ({
    user: {
      id: "00000000-0000-4000-8000-000000000099",
      role: "ADMIN",
      features: ["PLAN", "SHIFTS", "PAYROLL"]
    }
  })
}));

vi.mock("../stores/WorkspaceProvider", () => ({
  useWorkspace: () => ({
    stores: [{
      id: "00000000-0000-4000-8000-000000000010",
      name: "Центральный",
      address: "Главная, 1"
    }]
  })
}));

vi.mock("./api", () => ({
  adminKeys: { users: ["admin", "users"] },
  createAdminUser: vi.fn(),
  getAdminUsers: vi.fn(),
  resetAdminUserPassword: vi.fn(),
  updateAdminUser: vi.fn()
}));

const getUsersMock = vi.mocked(getAdminUsers);
const createUserMock = vi.mocked(createAdminUser);
const updateUserMock = vi.mocked(updateAdminUser);

const manager: AdminUser = {
  id: "00000000-0000-4000-8000-000000000001",
  email: "manager@example.com",
  displayName: "Руководитель",
  role: "MANAGER",
  active: true,
  passwordChangeRequired: false,
  allStores: false,
  storeIds: ["00000000-0000-4000-8000-000000000010"],
  features: ["PLAN", "PAYROLL"],
  lastLoginAt: null,
  version: 7
};

function renderPanel() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false }
    }
  });
  render(
    <QueryClientProvider client={client}>
      <UsersPanel />
    </QueryClientProvider>
  );
}

describe("UsersPanel", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getUsersMock.mockResolvedValue({
      items: [manager],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      hasNext: false,
      hasPrevious: false
    });
    updateUserMock.mockResolvedValue(manager);
    createUserMock.mockResolvedValue(manager);
  });

  it("edits stores and actions in one versioned request", async () => {
    const user = userEvent.setup();
    renderPanel();

    expect(await screen.findByText("План, Зарплата")).toBeInTheDocument();
    await user.click(screen.getByTitle("Изменить пользователя и доступ"));
    expect(screen.getByRole("checkbox", { name: /План/u })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: /Смены/u })).not.toBeChecked();
    expect(screen.getByRole("checkbox", { name: /Зарплата/u })).toBeChecked();

    await user.click(screen.getByRole("checkbox", { name: /Смены/u }));
    await user.click(screen.getByRole("checkbox", { name: /Зарплата/u }));
    await user.click(screen.getByRole("button", { name: "Сохранить" }));

    await waitFor(() => expect(updateUserMock).toHaveBeenCalledWith(manager.id, {
      displayName: "Руководитель",
      role: "MANAGER",
      active: true,
      storeIds: ["00000000-0000-4000-8000-000000000010"],
      features: ["PLAN", "SHIFTS"],
      version: 7
    }));
  });

  it("sends no explicit stores or actions for an administrator", async () => {
    const user = userEvent.setup();
    renderPanel();

    await screen.findByText("План, Зарплата");
    await user.click(screen.getByRole("button", { name: "Создать пользователя" }));
    await user.type(screen.getByLabelText("Email"), "admin2@example.com");
    await user.type(screen.getByLabelText("Имя"), "Второй администратор");
    await user.selectOptions(screen.getByLabelText("Роль"), "ADMIN");
    await user.type(screen.getByLabelText(/Временный пароль/u), "safe-password-2026");
    expect(screen.queryByRole("group", { name: "Доступные действия" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Сохранить" }));

    await waitFor(() => expect(createUserMock).toHaveBeenCalledWith({
      email: "admin2@example.com",
      temporaryPassword: "safe-password-2026",
      displayName: "Второй администратор",
      role: "ADMIN",
      storeIds: [],
      features: []
    }));
  });

  it("does not overwrite access values introduced by a newer server", async () => {
    getUsersMock.mockResolvedValue({
      items: [{ ...manager, features: ["PLAN", "UNKNOWN"] }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      hasNext: false,
      hasPrevious: false
    });

    renderPanel();

    expect(await screen.findByText("Доступ новой версии")).toBeInTheDocument();
    expect(screen.getByTitle("Редактирование недоступно: сервер вернул доступ новой версии"))
      .toBeDisabled();
  });
});
