import unittest
from metrics import read_metrics


class MetricsTest(unittest.TestCase):
    def test_gc_label_spaces_do_not_discard_measured_pause_time(self):
        key = 'jvm_gc_pause_seconds_sum{action="end of major GC",cause="Diagnostic Command",gc="G1 Old Generation"}'
        self.assertEqual(read_metrics(key + " 0.071\n"), {key: 0.071})


if __name__ == "__main__":
    unittest.main()
