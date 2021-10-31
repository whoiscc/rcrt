import json
import os
import flask
import time

import manager.util as util


SUBPATH = '/rcrt'
HTML_STUB = (
    '<script src = "app/core.js"></script>'
    '<script>window.rcrtMode = "{mode}"; startApp();</script>'
)

def serve(db_path):
    os.environ['FLASK_ENV'] = 'development'
    app = flask.Flask('Timeline Manager')
    app.config.from_mapping(DB_PATH=db_path)

    @app.route(SUBPATH + '/')
    def index():
        return HTML_STUB.format(mode='editable')

    @app.route(SUBPATH + '/app/<path:path>')
    def app_content(path):
        return flask.send_from_directory('app', path)

    @app.route(SUBPATH + '/db/<path:path>')
    def db_content(path):
        return flask.send_from_directory(app.config['DB_PATH'], path)

    @app.route(SUBPATH + '/edit/<entry_id>')
    def edit_entry(entry_id=None):
        raise NotImplementedError

    app.run(port=1102, debug=True)
    print()


def init(db_path):
    db_path.mkdir()
    meta = {}
    meta[util.generate_id(meta)] = {
        'type': 'text_inline',
        'text': 'This is my first post.',
        'time': time.time(),
    }
    meta[util.generate_id(meta)] = {
        'type': 'link',
        'url': 'https://github.com/sgdxbc/',
    }
    with open(db_path / 'meta.json', 'w') as meta_file:
        json.dump(meta, meta_file)
