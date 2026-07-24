const currencyFormatter = new Intl.NumberFormat("ru-RU", {
  style: "currency",
  currency: "RUB",
  maximumFractionDigits: 0
});

const numberFormatter = new Intl.NumberFormat("ru-RU", { maximumFractionDigits: 2 });
const percentFormatter = new Intl.NumberFormat("ru-RU", { maximumFractionDigits: 1 });

export function formatMoney(value: number | null | undefined): string {
  return value == null ? "—" : currencyFormatter.format(value);
}

export function formatNumber(value: number | null | undefined): string {
  return value == null ? "—" : numberFormatter.format(value);
}

export function formatPercent(value: number | null | undefined): string {
  return value == null ? "—" : `${percentFormatter.format(value)}%`;
}

export function formatCompactMoney(value: number | null | undefined): string {
  if (value == null) return "—";
  return new Intl.NumberFormat("ru-RU", {
    notation: "compact",
    maximumFractionDigits: 2
  }).format(value) + " ₽";
}
