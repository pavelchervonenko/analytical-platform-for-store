import { BellRing, BrainCircuit, ChartNoAxesCombined, Sparkles, UsersRound } from "lucide-react";
import "./styles.css";

const areas = [
  { icon: ChartNoAxesCombined, title: "Магазин" },
  { icon: UsersRound, title: "Команда" },
  { icon: BrainCircuit, title: "Сотрудники" }
];

export function InsightsPreviewPage() {
  return <div className="insights-preview-page">
    <header className="page-heading insights-preview-heading"><div><h1>AI-разбор</h1></div></header>

    <section className="panel insights-preview-hero">
      <div><span className="context-icon"><Sparkles /></span><h2>Главное за неделю</h2></div>
    </section>

    <div className="insights-preview-grid">{areas.map(({ icon: Icon, title }) => <article className="panel" key={title}><Icon /><h2>{title}</h2></article>)}</div>

    <section className="panel insights-notification-preview"><span><BellRing /></span><h2>Уведомления</h2></section>
  </div>;
}
