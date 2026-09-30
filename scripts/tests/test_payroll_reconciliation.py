import sys
from pathlib import Path
from decimal import Decimal as D
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'payroll-audit'))
from reconcile_snapshot import dated_assignment, fallback, shadow_month


class ReconciliationTest(unittest.TestCase):
    def test_server_fallback_order_and_ps5_boundary(self):
        self.assertEqual(fallback('IPAD_MAC', 'iPad', 'UNMAPPED'), 'TECH_TIER_2')
        self.assertEqual(fallback('IPAD_MAC', 'MacBook', 'UNMAPPED'), 'TECH_TIER_1')
        self.assertEqual(fallback('PODS_WATCH_OTHER_DEVICE', 'x_PS5_disc', 'TECH_TIER_2'), 'TECH_TIER_1')
        self.assertEqual(fallback('PODS_WATCH_OTHER_DEVICE', 'XPS5', 'TECH_TIER_2'), 'TECH_TIER_2')
        # Preserve the observed legacy error; the audit must not silently fix the baseline.
        self.assertEqual(fallback('PODS_WATCH_OTHER_DEVICE', 'кабель PS5', 'TECH_TIER_2'), 'TECH_TIER_1')

    def test_half_open_salary_dates(self):
        rows=[dict(valid_from='2026-01-01',valid_to='2026-02-01',role='A'),
              dict(valid_from='2026-02-01',valid_to=None,role='B')]
        self.assertEqual(dated_assignment(rows,'2026-01-31')['role'],'A')
        self.assertEqual(dated_assignment(rows,'2026-02-01')['role'],'B')
        self.assertIsNone(dated_assignment(rows,None))
        with self.assertRaises(ValueError):
            dated_assignment(rows+[rows[1]],'2026-02-01')

    def fixture(self, role='ACCESSORY', net='100', cost='20', sign=1, excluded=False):
        return dict(before=role, after=role, analytically_excluded=excluded,
                    business_date='2026-08-01', sign=sign, quantity=D('1'),
                    net_amount=D(net), cost_amount=None if cost is None else D(cost))

    def compute(self, facts):
        scheme=dict(achieved_percentage=D(20),missed_percentage=D(15),
                    achieved_tier1_rate=D(500),missed_tier1_rate=D(400),
                    achieved_tier2_rate=D(300),missed_tier2_rate=D(200))
        plan=dict(revenue_target=D(1000),accessory_share_target=D(100),service_share_target=D(100))
        return shadow_month(facts,'before',scheme,plan)

    def test_exclude_payroll_retains_revenue_but_analytic_exclude_does_not(self):
        result=self.compute([self.fixture(),self.fixture(role='EXCLUDE'),self.fixture(excluded=True)])
        self.assertEqual(result['revenue'],D(200))
        self.assertEqual(result['ACCESSORY'],D(100))
        self.assertEqual(result['fund'],D(15))

    def test_returns_signed_and_repair_uses_profit(self):
        result=self.compute([self.fixture(role='PAID_REPAIR'), self.fixture(role='PAID_REPAIR',net='50',cost='10',sign=-1)])
        self.assertEqual(result['PAID_REPAIR'],D(40))
        self.assertEqual(result['fund'],D(6))

    def test_missing_cost_and_unmapped_never_become_zero_fund(self):
        for fact in [self.fixture(role='PAID_REPAIR',cost=None),self.fixture(role='UNMAPPED')]:
            self.assertIsNone(self.compute([fact])['fund'])

    def test_daily_component_rounding_and_fixed_tech_rate(self):
        result=self.compute([self.fixture(role='TECH_TIER_1'),self.fixture(net='.10'),self.fixture(role='SERVICE',net='.10')])
        self.assertEqual(result['fund'],D('400.04'))


if __name__=='__main__':
    unittest.main()
