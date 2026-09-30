import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'payroll-audit'))
from review_catalog_remainder import validate_confirmations


class AdditionalRoleConfirmationTest(unittest.TestCase):
    def setUp(self):
        self.product=dict(id='p1',connection_id='c1',external_id='provider1',code='work1',
                          name='Synthetic repair',source_kind='SERVICE',version=3)
        self.entry=dict(product_id='p1',**{k:v for k,v in self.product.items() if k!='id'},
                        confirmed_payroll='PAID_REPAIR',decision_id='D-test',
                        source_reply='This is repair work',full_cost_confirmed=False)
        self.payload=dict(contract='additional-product-role-confirmations-v1',scope='REVIEW_ONLY',
                          snapshot_sha256='snapshot',reconciliation_sha256='review',
                          effective_from=None,historical_correction_approved=False,
                          confirmations=[self.entry])

    def validate(self,payload):
        return validate_confirmations(payload,'snapshot','review',{'p1':self.product})

    def test_role_confirmation_preserves_unapproved_date_and_cost(self):
        result=self.validate(self.payload)
        self.assertEqual(result['p1']['confirmed_payroll'],'PAID_REPAIR')
        self.assertFalse(result['p1']['full_cost_confirmed'])
        self.assertIsNone(self.payload['effective_from'])

    def test_each_identity_field_must_match(self):
        for key in ['connection_id','external_id','code','name','source_kind','version','product_id']:
            with self.subTest(key=key):
                payload=copy.deepcopy(self.payload)
                payload['confirmations'][0][key]='different'
                with self.assertRaises(ValueError):self.validate(payload)

    def test_reject_scope_hash_date_and_history_changes(self):
        for key,value in [('scope','APPLY'),('snapshot_sha256','other'),
                          ('reconciliation_sha256','other'),('effective_from','2026-10-01'),
                          ('historical_correction_approved',True)]:
            with self.subTest(key=key):
                payload=copy.deepcopy(self.payload);payload[key]=value
                with self.assertRaises(ValueError):self.validate(payload)

    def test_reject_duplicate_and_cost_assertion(self):
        payload=copy.deepcopy(self.payload);payload['confirmations'].append(copy.deepcopy(self.entry))
        with self.assertRaises(ValueError):self.validate(payload)
        payload=copy.deepcopy(self.payload);payload['confirmations'][0]['full_cost_confirmed']=True
        with self.assertRaises(ValueError):self.validate(payload)


if __name__=='__main__':unittest.main()
