import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from hs_matching.demo import demo_run
from hs_matching.web import create_app


class WebTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.config = {'TESTING': True, 'DEMO': True, 'RUNS_DIR': self.temp.name, 'SECRET_KEY': 'test'}
        self.app = create_app(self.config)
        self.client = self.app.test_client()
        self.client.get('/')
        with self.client.session_transaction() as session:
            token = session['csrf']
        self.data = {'csrf': token, 'query': 'Cotton T-shirts', 'approaches': ['llm_direct', 'embeddings', 'rag'],
                     'top_k': '3', 'retrieval_k': '20', 'model': 'test-model', 'timeout': '60',
                     'max_output_tokens': '2048', 'temperature': '', 'reasoning_effort': '', 'scenario': 'standard'}

    def test_demo_three_columns_and_export(self):
        with patch('hs_matching.web.WebRunner') as engine:
            response = self.client.post('/', data=self.data)
        engine.assert_not_called()
        self.assertEqual(response.status_code, 302)
        html = self.client.get(response.location).get_data(as_text=True)
        self.assertEqual(html.count('class="result-column '), 3)
        self.assertIn('2.84 s', html)
        self.assertIn('0.46 s', html)
        self.assertIn('3.72 s', html)
        self.assertIn('Similarité cosinus', html)
        self.assertIn('CLASSEMENT SUR LES TEXTES FOURNIS', html)
        download = self.client.get(response.location + '/download')
        self.assertEqual(download.status_code, 200)
        self.assertTrue(download.headers['Content-Disposition'].startswith('attachment'))
        self.assertTrue(json.loads(download.data)['demo'])
        download.close()
        # Recharger les résultats ne crée aucun nouvel appel/run.
        self.client.get(response.location)
        self.assertEqual(len(list(Path(self.temp.name).glob('*.json'))), 1)

    def test_sequential_order_failure_isolation_and_total_duration(self):
        calls = []
        def runner(approach, options):
            calls.append(approach)
            if approach == 'embeddings':
                raise FileNotFoundError()
            run = demo_run(approach, options)
            return run
        app = create_app({**self.config, 'DEMO': False, 'ENV_FILE': self.temp.name + '/absent'}, runner=runner)
        client = app.test_client()
        client.get('/')
        with client.session_transaction() as session:
            self.data['csrf'] = session['csrf']
        with patch('hs_matching.web.perf_counter', side_effect=[1, 3, 4, 7, 8, 12]):
            response = client.post('/', data=self.data, follow_redirects=True)
        self.assertEqual(calls, ['llm_direct', 'embeddings', 'rag'])
        self.assertEqual(response.status_code, 200)
        result = json.loads(next(Path(self.temp.name).glob('*.json')).read_text())
        self.assertEqual([r['predictions'][0]['status'] for r in result['runs']], ['ok', 'error', 'ok'])
        self.assertEqual([run['predictions'][0]['metadata']['total_duration_seconds'] for run in result['runs']], [2, 3, 4])
        self.assertIn('au total', response.get_data(as_text=True))

    def test_selected_subset_only(self):
        self.data['approaches'] = ['rag']
        response = self.client.post('/', data=self.data, follow_redirects=True)
        self.assertEqual(response.get_data(as_text=True).count('class="result-column '), 1)

    def test_invalid_inputs_and_csrf_never_run(self):
        for replacement in [{'approaches': []}, {'retrieval_k': '1'}, {'query': ''}, {'top_k': 'bad'}, {'csrf': 'bad'}, {'approaches': ['unknown']}]:
            with self.subTest(replacement=replacement):
                response = self.client.post('/', data={**self.data, **replacement})
                self.assertEqual(response.status_code, 400)
        self.assertEqual(list(Path(self.temp.name).glob('*.json')), [])

    def test_external_text_is_escaped(self):
        self.data['query'] = '<script>alert("xss")</script>'
        response = self.client.post('/', data=self.data, follow_redirects=True)
        html = response.get_data(as_text=True)
        self.assertNotIn('<script>alert(', html)
        self.assertIn('&lt;script&gt;', html)

    def test_states_and_safe_download_path(self):
        self.data['scenario'] = 'states'
        response = self.client.post('/', data=self.data, follow_redirects=True)
        html = response.get_data(as_text=True)
        for label in ['À préciser', 'Exécution interrompue', 'Aucun classement proposé']:
            self.assertIn(label, html)
        self.assertEqual(self.client.get('/comparisons/not-an-id/download').status_code, 404)
        self.assertEqual(self.client.get('/comparisons/' + '0' * 32).status_code, 404)

    def test_save_failure_preserves_results(self):
        with patch('hs_matching.web.save_run', side_effect=OSError()):
            response = self.client.post('/', data=self.data)
        self.assertEqual(response.status_code, 500)
        self.assertIn('sauvegarde a échoué', response.get_data(as_text=True))
        self.assertIn('610910', response.get_data(as_text=True))
