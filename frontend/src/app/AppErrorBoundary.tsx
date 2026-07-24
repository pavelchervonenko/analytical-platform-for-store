import { Component, type ErrorInfo, type ReactNode } from "react";

interface Props {
  children: ReactNode;
}

interface State {
  failed: boolean;
}

export class AppErrorBoundary extends Component<Props, State> {
  state: State = { failed: false };

  static getDerivedStateFromError(): State {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    if (import.meta.env.DEV) {
      console.error("Unhandled application error", error, info.componentStack);
    }
  }

  render(): ReactNode {
    if (this.state.failed) {
      return (
        <main className="fatal-state">
          <div className="fatal-state__card">
            <div className="brand-mark" aria-hidden="true">S</div>
            <h1>Не удалось открыть кабинет</h1>
            <p>Интерфейс столкнулся с непредвиденной ошибкой. Обновите страницу, чтобы продолжить.</p>
            <button className="button button--primary" type="button" onClick={() => window.location.reload()}>
              Обновить страницу
            </button>
          </div>
        </main>
      );
    }
    return this.props.children;
  }
}
