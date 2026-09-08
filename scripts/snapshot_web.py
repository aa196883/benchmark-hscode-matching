#!/usr/bin/env python3
"""Captures reproductibles du mode démo avec un serveur Flask temporaire."""
import argparse
from pathlib import Path
import sys
import tempfile
from threading import Thread

if __package__ in (None, ''):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from playwright.sync_api import sync_playwright
from werkzeug.serving import make_server
from hs_matching.web import create_app


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, default=Path('artifacts/screenshots'))
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as runs:
        server = make_server('127.0.0.1', 0, create_app({'DEMO': True, 'RUNS_DIR': runs}))
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch()
                page = browser.new_page(viewport={'width': 1512, 'height': 1100}, device_scale_factor=1)
                errors = []
                page.on('pageerror', lambda error: errors.append(str(error)))
                page.goto(f'http://127.0.0.1:{server.server_port}')
                page.screenshot(path=str(args.output_dir / 'empty-desktop.png'), full_page=True)
                page.get_by_role('button', name='Comparer les approches').click()
                page.wait_for_url('**/comparisons/*')
                assert page.locator('.result-column').count() == 3
                assert page.locator('.duration').count() == 3
                page.screenshot(path=str(args.output_dir / 'comparison-desktop.png'), full_page=True)
                page.set_viewport_size({'width': 390, 'height': 844})
                assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth'), 'Débordement mobile'
                page.screenshot(path=str(args.output_dir / 'comparison-mobile.png'), full_page=True)
                page.set_viewport_size({'width': 1512, 'height': 1100})
                page.locator('.advanced summary').click()
                page.locator('select[name=scenario]').select_option('states')
                page.get_by_role('button', name='Comparer les approches').click()
                page.wait_for_url('**/comparisons/*')
                page.get_by_role('heading', name='Exécution interrompue').wait_for()
                page.screenshot(path=str(args.output_dir / 'states-desktop.png'), full_page=True)
                assert not errors, errors
                browser.close()
        finally:
            server.shutdown()
            thread.join()
    print(f'Captures enregistrées dans {args.output_dir}')


if __name__ == '__main__':
    main()
