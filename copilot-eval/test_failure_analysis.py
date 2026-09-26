import unittest
from failure_analysis import classify


class FailureAnalysisTest(unittest.TestCase):
    def test_correct_report_time_with_context_is_not_mislabeled(self):
        row = dict(task={'id': 'last-report'}, deterministic_errors=['contradictory report time'],
                   answer='Latest report 2024-01-08T11:55:00Z; transition 2024-01-07T12:00:00Z.')
        self.assertEqual(classify(row)['primaryCause'], 'other')

    def test_wrong_report_time_retains_time_cause(self):
        row = dict(task={'id': 'last-report'}, deterministic_errors=['contradictory report time'],
                   answer='Latest report 2024-01-08T12:00:00Z.')
        self.assertEqual(classify(row)['primaryCause'], 'time-range conversion')

    def test_count_summary_cannot_answer_which_vessels(self):
        row = dict(task={'id': 'savannah-week', 'query': {'kind': 'events'}},
                   deterministic_errors=['incorrect count', 'missing/contradictory fact {"kind": "asset", "value": 366000102}'],
                   tool_evidence=[{'tool': 'aggregateEvents', 'arguments': '{}', 'result': '{"distinctAssets":2}'}])
        self.assertEqual(classify(row)['primaryCause'], 'wrong tool')

    def test_rejected_tool_arguments_are_retained_as_evidence(self):
        row = dict(task={'id': 'la-yesterday'}, tool_evidence=[
            {'tool': 'listFences', 'arguments': '{"":{}}',
             'result': '{"error":"Invalid arguments or unavailable tenant-owned resource"}'}])
        result = classify(row)
        self.assertEqual(result['primaryCause'], 'wrong arguments')
        self.assertIn('{"":{}}', result['causes'][0]['evidence'])

    def test_correct_total_and_breakdown_are_not_arithmetic_error(self):
        row = dict(task={'id': 'weekly-arrivals'}, deterministic_errors=['incorrect count'],
                   answer='20 arrival events: Los Angeles 8 arrivals, Seattle 6 arrivals, Newark 6 arrivals.',
                   ground_truth={'requiredFacts': [{'kind': 'count', 'value': 20}]},
                   tool_evidence=[{'tool': 'aggregateEvents', 'arguments': '{}',
                       'result': '{"totalEvents":20,"ports":[{"events":8},{"events":6}]}'}])
        self.assertEqual(classify(row)['primaryCause'], 'other')
        row['answer'] = '**Total Departure Events: 14**; Oakland 8 departure events, Houston 6 departure events.'
        row['ground_truth']['requiredFacts'][0]['value'] = 14
        row['tool_evidence'][0]['result'] = '{"totalEvents":14,"ports":[{"events":8},{"events":6}]}'
        self.assertEqual(classify(row)['primaryCause'], 'other')

    def test_budget_reserves_unknown_outage_setup_attempts(self):
        from bedrock import unknown_attempts
        row = {'responses': [{'reply': {'evaluation': {'attempts': [
            {'success': True, 'usageUnknown': False}]}}}],
            'setup_responses': [{'evaluation': {'attempts': [
                {'success': False, 'usageUnknown': True, 'error': 'ServiceUnavailableException'},
                {'success': False, 'usageUnknown': True, 'error': 'ValidationException'}]}}]}
        self.assertEqual(unknown_attempts([row]), 1)

    def test_malformed_argument_shapes_do_not_abort_analysis(self):
        row = dict(task={'id': 'la-yesterday'}, tool_evidence=[
            {'tool': 'listEvents', 'arguments': '[]', 'result': '{}'}])
        self.assertEqual(classify(row)['primaryCause'], 'wrong arguments')
        row['tool_evidence'][0]['arguments'] = '{"fromTs":"not a timestamp"}'
        row['ground_truth'] = {'expectedArguments': {'listEvents': {'fromTs': 1000}}}
        self.assertEqual(classify(row)['primaryCause'], 'time-range conversion')

    def test_missing_followup_usage_is_not_hidden_by_first_turn_receipt(self):
        from bedrock import unknown_attempts
        row = {'task': {'turns': ['status', 'followup']}, 'error': 'HTTP Error 504',
               'usage_incomplete': True, 'responses': [{'reply': {'evaluation': {'attempts': [
                   {'success': True, 'usageUnknown': False}]}}}]}
        self.assertEqual(unknown_attempts([row]), 5)
        row['responses'][-1]['reply'].update(failed=True, evaluation={'attempts': [
            {'success': False, 'usageUnknown': True, 'error': 'ValidationException'}]})
        self.assertEqual(unknown_attempts([row]), 0)
