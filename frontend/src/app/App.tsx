import { lazy, Suspense } from "react";
import { Navigate, Route, Routes } from "react-router";
import { ChangePasswordPage } from "../auth/ChangePasswordPage";
import { LoginPage } from "../auth/LoginPage";
import { AdminGate, AnonymousGate, PasswordChangeGate, SessionGate } from "../auth/SessionGates";
import { AppShell } from "./AppShell";

const OverviewPage = lazy(async () => { const module = await import("../dashboard/OverviewPage"); return { default: module.OverviewPage }; });
const EmployeesPage = lazy(async () => { const module = await import("../employees/EmployeesPage"); return { default: module.EmployeesPage }; });
const EmployeeCardPage = lazy(async () => { const module = await import("../employees/EmployeeCardPage"); return { default: module.EmployeeCardPage }; });
const PlanSchedulePage = lazy(async () => { const module = await import("../plan-schedule/PlanSchedulePage"); return { default: module.PlanSchedulePage }; });
const PayrollPage = lazy(async () => { const module = await import("../payroll/PayrollPage"); return { default: module.PayrollPage }; });
const QualityPage = lazy(async () => { const module = await import("../quality/QualityPage"); return { default: module.QualityPage }; });
const ReportsPage = lazy(async () => { const module = await import("../reports/ReportsPage"); return { default: module.ReportsPage }; });
const AdminPage = lazy(async () => { const module = await import("../admin/AdminPage"); return { default: module.AdminPage }; });

const ProfilePage = lazy(async () => { const module = await import("../auth/ProfilePage"); return { default: module.ProfilePage }; });
const InsightsPreviewPage = lazy(async () => { const module = await import("../insights/InsightsPreviewPage"); return { default: module.InsightsPreviewPage }; });
const insightsPreviewEnabled = import.meta.env.DEV
  || import.meta.env.VITE_ENABLE_INSIGHTS_PREVIEW === "true";

function PageLoader() { return <div className="page-loader" aria-live="polite"><span className="spinner" /><span>Загружаем раздел…</span></div>; }

export function App() {
  return <Suspense fallback={<PageLoader />}><Routes>
    <Route element={<AnonymousGate />}><Route path="/login" element={<LoginPage />} /></Route>
    <Route element={<PasswordChangeGate />}><Route path="/change-password" element={<ChangePasswordPage />} /></Route>
    <Route element={<SessionGate />}><Route element={<AppShell />}>
      <Route index element={<Navigate to="/overview" replace />} />
      <Route path="/overview" element={<OverviewPage />} />
      <Route path="/employees" element={<EmployeesPage />} />
      <Route path="/employees/:employeeId" element={<EmployeeCardPage />} />
      <Route path="/plan" element={<PlanSchedulePage />} />
      <Route path="/payroll" element={<PayrollPage />} />
      <Route path="/quality" element={<QualityPage />} />
      <Route path="/reports" element={<ReportsPage />} />
      <Route path="/profile" element={<ProfilePage />} />
      <Route path="/insights" element={insightsPreviewEnabled ? <InsightsPreviewPage /> : <Navigate to="/overview" replace />} />
      <Route path="/admin" element={<AdminGate><AdminPage /></AdminGate>} />
    </Route></Route>
    <Route path="*" element={<Navigate to="/overview" replace />} />
  </Routes></Suspense>;
}
