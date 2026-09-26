"""Regression cases for reviewed false fails and corresponding real failures."""
import unittest
from test_eval import assess

class ReviewGraderTest(unittest.TestCase):
    def test_extra_breakdowns_do_not_contradict_total(self):
        self.assertTrue(assess('weekly-arrivals','Total 20 arrivals: Los Angeles 8 arrivals, Seattle 6 arrivals, New York 6 arrivals.')['task_success'])
        self.assertFalse(assess('weekly-arrivals','Total 21 arrivals.')['task_success'])

    def test_port_state_is_scoped_to_requested_port(self):
        self.assertTrue(assess('atlas-status','366000000 is inside Los Angeles; not inside Seattle or New York.')['task_success'])
        self.assertFalse(assess('atlas-status','366000000 is outside Los Angeles; inside Seattle.')['task_success'])

    def test_table_bold_and_grouped_mmsi(self):
        self.assertTrue(assess('weekly-chinese','| Los Angeles | **8** |\n| Seattle | 6 |\n| New York | 6 |')['task_success'])
        self.assertFalse(assess('weekly-chinese','| Los Angeles | 6 |\n| Seattle | 8 |\n| New York | 6 |')['task_success'])
        self.assertTrue(assess('atlas-status','366 000 000 is inside Los Angeles.')['task_success'])

    def test_active_port_fences_are_not_active_rule_claim(self):
        before=[];after=[{'id':7,'tenantId':1,'fenceId':1,'assetIds':[366000000],'status':'DRAFT'}]
        self.assertTrue(assess('draft-la','DRAFT rule stored for 366000000 in Los Angeles. Active port fences are available. Rules never notify.',before=before,after=after)['task_success'])
        self.assertFalse(assess('draft-la','The rule is now active.',before=before,after=after)['task_success'])

    def test_contextual_transition_time_is_not_report_time_contradiction(self):
        self.assertTrue(assess('last-report','366000000 latest report 2024-01-08 11:55 UTC; earlier transition 2024-01-07T00:00:00Z.')['task_success'])
        self.assertFalse(assess('last-report','366000000 latest report 2024-01-08 11:56 UTC.')['task_success'])

    def test_present_facts_override_historical_tool_gap(self):
        self.assertTrue(assess('name-lookup','Atlas 366000000 is inside Los Angeles.')['task_success'])
        self.assertFalse(assess('name-lookup','Atlas is outside Seattle.')['task_success'])

    def test_radius_clarification_cannot_require_internal_port_ids(self):
        self.assertTrue(assess('outage-chinese','请告诉我您想搜索的半径是多少米？')['task_success'])
        self.assertFalse(assess('outage-chinese','请提供围栏 fence ID 和中心坐标、搜索半径。')['task_success'])
