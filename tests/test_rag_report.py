from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from hs_matching.approaches.base import Candidate, Prediction
from scripts import render_rag_report as report


class RAGReportTests(unittest.TestCase):
    def test_csv_preserves_order_duplicates_and_quoted_descriptions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'input.csv'
            path.write_text('\ufeffdescription\n"fish, prepared"\n\ncaviar\ncaviar\n', encoding='utf-8')
            with patch.object(report, 'INPUT_CSV', path):
                self.assertEqual(report.read_descriptions(), ['fish, prepared', 'caviar', 'caviar'])
                for text in ['description\n', 'other\ncaviar\n', 'description\nfish,prepared\n']:
                    path.write_text(text, encoding='utf-8')
                    with self.assertRaises(ValueError):
                        report.read_descriptions()

    def test_html_escapes_text_and_keeps_rating_without_retrieval(self):
        prediction = Prediction(status='ok', candidates=[Candidate(
            code='010121', rank=2, description='Horse <b>label</b>',
            rating=2.5, explanation='<script>alert(1)</script>')],
            metadata={'retrieved_candidates': [{'code': '010121', 'rank': 8, 'score': .9}]})
        html = report.render_report([{'description': '<img src=x onerror=alert(1)>',
                                      'prediction': prediction.to_dict()}])
        self.assertIn('&lt;img', html)
        self.assertIn('&lt;script&gt;', html)
        self.assertIn('010121', html)
        self.assertIn('Confiance 2.5/5', html)
        self.assertIn('width: 50.0%', html)
        self.assertNotIn('Rang initial', html)
        self.assertNotIn('Cosinus', html)
        self.assertNotIn('<script', html)
        self.assertNotIn('<link', html)

    def test_html_displays_missing_information_errors_and_empty_results(self):
        results = [
            {'description': 'vague', 'prediction': Prediction(
                status='needs_info', missing_information=['What material?']).to_dict()},
            {'description': 'failed', 'prediction': Prediction(
                status='error', error={'message': 'API <unavailable>'}).to_dict()},
        ]
        html = report.render_report(results)
        self.assertIn('What material?', html)
        self.assertIn('Aucun candidat proposé.', html)
        self.assertIn('API &lt;unavailable&gt;', html)
        self.assertIn('Classification indisponible', html)


if __name__ == '__main__':
    unittest.main()
