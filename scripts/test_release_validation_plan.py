"""Synthetic tests of bounded coverage and fail-closed time estimates."""
import copy
import math
import unittest

from release_validation_plan import budget, representatives


class CoverageTests(unittest.TestCase):
    def test_real_size_inventory_keeps_all_role_state_boundaries(self):
        items = [dict(book=i, role=role, state=state, page=page)
                 for i in range(990) for role in ('ADMIN', 'LIBRARIAN', 'READER')
                 for state in ('EMPTY', 'NONEMPTY') for page in ('FIRST', 'LAST', 'TAIL')]
        original = copy.deepcopy(items)
        classify = lambda item: (item['role'], item['state'], item['page'])
        plan = representatives(items, classify, max_cases=18)
        self.assertEqual(plan['selectedCount'], 18)
        self.assertEqual({classify(item) for item in plan['items']}, {classify(item) for item in items})
        self.assertEqual(items, original)
        reversed_plan = representatives(list(reversed(items)), classify, max_cases=18)
        self.assertEqual(plan['selectedPlanSha256'], reversed_plan['selectedPlanSha256'])
        self.assertFalse(plan['completeOriginalPlanExecuted'])

    def test_limit_refuses_instead_of_dropping_coverage(self):
        with self.assertRaisesRegex(ValueError, 'WITHOUT_TRUNCATION'):
            representatives([{'role': role} for role in ('ADMIN', 'READER')], lambda item: (item['role'],), max_cases=1)

    def test_duplicate_and_empty_class_refused(self):
        with self.assertRaisesRegex(ValueError, 'DUPLICATE'):
            representatives([{'id': 1}, {'id': 1}], lambda item: (item['id'],), max_cases=2)
        with self.assertRaisesRegex(ValueError, 'COVERAGE_CLASS'):
            representatives([{'id': 1}], lambda _: (), max_cases=2)


class BudgetTests(unittest.TestCase):
    def group(self, **overrides):
        return dict(name='restore', maintenance=True, requests=100, secondsPerRequest=2, fixedSeconds=60, **overrides)

    def test_actual_scale_old_loop_exceeds_budget(self):
        old = self.group()
        old['requests'] = 14850
        result = budget([old], recovery_seconds=180)
        self.assertFalse(result['budgetSatisfied'])
        self.assertFalse(result['productionActionsAuthorized'])

    def test_online_regression_is_separate_and_recovery_is_reserved(self):
        online = self.group()
        online.update(name='preflight', maintenance=False, requests=10000)
        result = budget([online, self.group()], recovery_seconds=180)
        self.assertTrue(result['budgetSatisfied'])
        self.assertGreater(result['onlineWorkSeconds'], result['maintenanceWorkSeconds'])
        self.assertEqual(result['requiredMaintenanceSeconds'], result['maintenanceWorkSeconds'] + 180)

    def test_unknown_nan_infinite_and_zero_request_cost_refused(self):
        for value in (None, math.nan, math.inf, -1, 0, True):
            with self.subTest(value=value):
                group = self.group()
                group['secondsPerRequest'] = value
                with self.assertRaises(ValueError):
                    budget([group], recovery_seconds=180)

    def test_exact_count_fields_and_nonempty_work_required(self):
        for change in ({'requests': True}, {'maintenance': 'yes'}, {'fixedSeconds': None}, {'requests': 0, 'fixedSeconds': 0}):
            group = self.group()
            group.update(change)
            with self.subTest(change=change), self.assertRaises(ValueError):
                budget([group], recovery_seconds=180)

    def test_reserve_and_safety_factor_cannot_be_omitted_or_reduced(self):
        for reserve, factor in ((0, 1.25), (180, 0.5), (180, math.nan)):
            with self.subTest(reserve=reserve, factor=factor), self.assertRaises(ValueError):
                budget([self.group()], recovery_seconds=reserve, safety_factor=factor)


if __name__ == '__main__':
    unittest.main()
