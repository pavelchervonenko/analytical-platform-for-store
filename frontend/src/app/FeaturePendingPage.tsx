import { Construction } from "lucide-react";

export function FeaturePendingPage({ title }: { title: string }) {
  return (
    <section className="feature-pending">
      <Construction size={26} />
      <p className="eyebrow">Следующий вертикальный срез</p>
      <h1>{title}</h1>
      <p>Backend-контракт уже изучен. Экран будет подключен к фактическому API без демонстрационных данных.</p>
    </section>
  );
}
