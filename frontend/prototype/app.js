const icons = {
  grid: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
  users: '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75"/>',
  calendar: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M16 3v4M8 3v4M3 11h18"/>',
  wallet: '<path d="M20 7V5a2 2 0 0 0-2-2H5a3 3 0 0 0 0 6h15v12H5a3 3 0 0 1-3-3V6"/><path d="M16 13h4"/>',
  settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.88l.06.06-2.83 2.83-.06-.06A1.7 1.7 0 0 0 15 19.4a1.7 1.7 0 0 0-1 .6 1.7 1.7 0 0 0-.4 1.1V21H9v-.1A1.7 1.7 0 0 0 7.6 19.4a1.7 1.7 0 0 0-1.88.34l-.06.06-2.83-2.83.06-.06A1.7 1.7 0 0 0 3.6 15a1.7 1.7 0 0 0-.6-1 1.7 1.7 0 0 0-1.1-.4H2V9h.1A1.7 1.7 0 0 0 3.6 7.6a1.7 1.7 0 0 0-.34-1.88l-.06-.06 2.83-2.83.06.06A1.7 1.7 0 0 0 8 3.6a1.7 1.7 0 0 0 1-.6 1.7 1.7 0 0 0 .4-1.1V2H14v.1A1.7 1.7 0 0 0 15.4 3.6a1.7 1.7 0 0 0 1.88-.34l.06-.06 2.83 2.83-.06.06A1.7 1.7 0 0 0 19.4 8c.12.37.34.7.65.94.3.24.68.37 1.05.4h.1V14h-.1a1.7 1.7 0 0 0-1.7 1Z"/>',
  menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
  chevron: '<path d="m9 18 6-6-6-6"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  bell: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M13.7 21a2 2 0 0 1-3.4 0"/>',
  database: '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v6c0 1.7 3.6 3 8 3s8-1.3 8-3V5M4 11v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6"/>',
  trend: '<path d="m3 17 6-6 4 4 8-8"/><path d="M14 7h7v7"/>',
  receipt: '<path d="M6 2v20l3-2 3 2 3-2 3 2V2l-3 2-3-2-3 2-3-2Z"/><path d="M9 9h6M9 13h6"/>',
  'plus-circle': '<circle cx="12" cy="12" r="9"/><path d="M12 8v8M8 12h8"/>',
  arrow: '<path d="M5 12h14M13 6l6 6-6 6"/>',
  phone: '<rect x="6" y="2" width="12" height="20" rx="2"/><path d="M10 18h4"/>',
  devices: '<rect x="2" y="4" width="15" height="12" rx="2"/><path d="M8 20h3M9.5 16v4"/><rect x="18" y="8" width="4" height="9" rx="1"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>',
  filter: '<path d="M4 5h16M7 12h10M10 19h4"/>',
  edit: '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L8 18l-4 1 1-4Z"/>',
  close: '<path d="m6 6 12 12M18 6 6 18"/>',
  warning: '<path d="M10.3 3.7 2.2 18a2 2 0 0 0 1.7 3h16.2a2 2 0 0 0 1.7-3L13.7 3.7a2 2 0 0 0-3.4 0Z"/><path d="M12 9v4M12 17h.01"/>',
  'check-circle': '<circle cx="12" cy="12" r="9"/><path d="m8 12 3 3 5-6"/>',
  history: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5M12 7v5l3 2"/>',
  'minus-circle': '<circle cx="12" cy="12" r="9"/><path d="M8 12h8"/>',
  plus: '<path d="M12 5v14M5 12h14"/>'
};

document.querySelectorAll('[data-icon]').forEach((element) => {
  const icon = icons[element.dataset.icon];
  if (icon) element.innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true">${icon}</svg>`;
});

const pageTitles = {
  overview: 'Обзор магазина',
  employees: 'Сотрудники',
  schedule: 'План магазина',
  payroll: 'Зарплата'
};

function navigateTo(view) {
  if (!pageTitles[view]) return;
  document.querySelectorAll('.app-view').forEach((page) => page.classList.toggle('is-active', page.dataset.page === view));
  document.querySelectorAll('[data-view]').forEach((button) => button.classList.toggle('is-active', button.dataset.view === view));
  document.title = `${pageTitles[view]} — Store Analytics`;
  history.replaceState(null, '', `#${view}`);
  window.scrollTo({ top: 0, behavior: 'smooth' });
  closeMobileMenu();
}

document.querySelectorAll('[data-view]').forEach((button) => button.addEventListener('click', () => navigateTo(button.dataset.view)));
document.querySelectorAll('[data-nav]').forEach((button) => button.addEventListener('click', () => navigateTo(button.dataset.nav)));

const initialView = location.hash.slice(1);
if (pageTitles[initialView]) navigateTo(initialView);

const storeSwitcher = document.getElementById('storeSwitcher');
const storeMenu = document.getElementById('storeMenu');
const stores = {
  future: { name: 'Future Store', address: 'Ленинский проспект, 30' },
  mobi: { name: 'Моби Сфера', address: 'ул. Театральная, 17' }
};

storeSwitcher.addEventListener('click', () => {
  const open = storeMenu.classList.toggle('is-open');
  storeSwitcher.setAttribute('aria-expanded', String(open));
});

document.querySelectorAll('.store-option').forEach((button) => {
  button.addEventListener('click', () => {
    const selected = stores[button.dataset.store];
    document.getElementById('currentStoreName').textContent = selected.name;
    document.getElementById('currentStoreAddress').textContent = selected.address;
    document.querySelector('.store-switcher__avatar').textContent = button.dataset.store === 'future' ? 'FS' : 'МС';
    document.querySelectorAll('.store-option').forEach((option) => {
      const isSelected = option === button;
      option.classList.toggle('is-selected', isSelected);
      option.setAttribute('aria-selected', String(isSelected));
    });
    storeMenu.classList.remove('is-open');
    storeSwitcher.setAttribute('aria-expanded', 'false');
    showToast(`Выбран магазин «${selected.name}»`);
  });
});

document.addEventListener('click', (event) => {
  if (!event.target.closest('.store-control')) {
    storeMenu.classList.remove('is-open');
    storeSwitcher.setAttribute('aria-expanded', 'false');
  }
});

document.querySelectorAll('[data-period]').forEach((button) => {
  button.addEventListener('click', () => {
    document.querySelectorAll('[data-period]').forEach((item) => item.classList.toggle('is-active', item === button));
    const labels = { week: '20–26 июля 2026', month: '1–21 июля 2026', custom: '10–21 июля 2026' };
    document.getElementById('overviewPeriod').textContent = labels[button.dataset.period];
    showToast(`Период: ${labels[button.dataset.period]}`);
  });
});

const scenarioValues = {
  completed: { accrued: '684 720 ₽', pool: '+118 420 ₽', payable: '422 220 ₽', status: 'Текущая выручка соответствует сценарию «План выполнен»' },
  missed: { accrued: '566 300 ₽', pool: 'Базовые ставки', payable: '303 800 ₽', status: 'Альтернативный сценарий: план не выполнен' }
};

document.querySelectorAll('[data-scenario]').forEach((button) => {
  button.addEventListener('click', () => {
    document.querySelectorAll('[data-scenario]').forEach((item) => item.classList.toggle('is-active', item === button));
    const scenario = scenarioValues[button.dataset.scenario];
    document.getElementById('payrollAccrued').textContent = scenario.accrued;
    document.getElementById('payrollPool').textContent = scenario.pool;
    document.getElementById('payrollPayable').textContent = scenario.payable;
    document.querySelector('.selected-scenario').lastChild.textContent = scenario.status;
  });
});

const employeeSearch = document.getElementById('employeeSearch');
employeeSearch.addEventListener('input', () => {
  const query = employeeSearch.value.trim().toLocaleLowerCase('ru');
  document.querySelectorAll('.employee-row').forEach((row) => {
    row.hidden = !row.dataset.employee.toLocaleLowerCase('ru').includes(query);
  });
});

const drawer = document.getElementById('employeeDrawer');
const drawerBackdrop = document.getElementById('drawerBackdrop');

function openEmployeeDrawer(name) {
  document.getElementById('employeeDrawerTitle').textContent = name;
  document.getElementById('drawerEmployeeName').textContent = name;
  drawer.classList.add('is-open');
  drawerBackdrop.classList.add('is-open');
  drawer.setAttribute('aria-hidden', 'false');
  document.body.style.overflow = 'hidden';
  document.getElementById('closeEmployeeDrawer').focus();
}

function closeEmployeeDrawer() {
  drawer.classList.remove('is-open');
  drawerBackdrop.classList.remove('is-open');
  drawer.setAttribute('aria-hidden', 'true');
  document.body.style.overflow = '';
}

document.querySelectorAll('[data-employee-detail]').forEach((button) => button.addEventListener('click', () => openEmployeeDrawer(button.dataset.employeeDetail)));
document.getElementById('closeEmployeeDrawer').addEventListener('click', closeEmployeeDrawer);
drawerBackdrop.addEventListener('click', closeEmployeeDrawer);

const sidebar = document.getElementById('sidebar');
const mobileMenuButton = document.getElementById('mobileMenuButton');

function closeMobileMenu() {
  sidebar.classList.remove('is-open');
  mobileMenuButton.setAttribute('aria-expanded', 'false');
}

mobileMenuButton.addEventListener('click', () => {
  const open = sidebar.classList.toggle('is-open');
  mobileMenuButton.setAttribute('aria-expanded', String(open));
});

document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') {
    closeEmployeeDrawer();
    closeMobileMenu();
    storeMenu.classList.remove('is-open');
  }
});

let toastTimer;
function showToast(message) {
  const toast = document.getElementById('toast');
  document.getElementById('toastText').textContent = message;
  toast.classList.add('is-visible');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove('is-visible'), 2600);
}

document.querySelectorAll('[data-toast]').forEach((button) => button.addEventListener('click', () => showToast(button.dataset.toast)));

document.querySelector('.prototype-banner__close').addEventListener('click', () => document.body.classList.add('banner-hidden'));

document.getElementById('calculatePayroll').addEventListener('click', () => {
  showToast('Прототип: расчет создан, переход к ревизии №1');
  const steps = document.querySelectorAll('.workflow-steps li');
  steps[1].classList.remove('is-active');
  steps[1].classList.add('is-complete');
  steps[1].querySelector(':scope > span').innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true">${icons.check}</svg>`;
  steps[2].classList.add('is-active');
});
