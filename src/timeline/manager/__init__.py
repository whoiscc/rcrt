import json
import os
import flask

import manager.util as util


SUBPATH = '/rcrt'
HTML_STUB = (
    '<link rel="shortcut icon" type="image/png" href="app/favicon.png"/>'
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

    @app.route(SUBPATH + '/edit/<path>', methods=['POST'])
    def edit_entry(path=None):
        if path == 'meta':
            update = flask.request.get_json()
            with open(db_path / 'meta.json') as meta_file:
                meta = json.load(meta_file)
            for identifier in update:
                assert identifier in meta
                meta[identifier] = update[identifier]
            with open(db_path / 'meta.json', 'w') as meta_file:
                json.dump(meta, meta_file)
        else:
            with open(db_path / path, 'wb') as entry_file:
                entry_file.write(flask.request.get_data())
        return 'Ok'

    app.run(port=1102, debug=True)
    print()


def init(db_path):
    db_path.mkdir()
    meta = {}
    meta[util.generate_id(meta)] = {
        'type': 'post',
        'text': 'This is my first post.\nThe second sentence starts from a new line.',
        'time': util.past_day(0),
    }
    link_id = util.generate_id(meta)
    meta[link_id] = {
        'type': 'link',
        'url': 'https://github.com/sgdxbc/',
    }
    article_id = util.generate_id(meta)
    meta[article_id] = {
        'type': 'article',
        'title': 'Title of My First Article',
        'time': util.past_day(2),
        'series': 'Series of Test Things',
    }
    with open(db_path / 'meta.json', 'w') as meta_file:
        json.dump(meta, meta_file)
    with open(db_path / f'{article_id}.txt', 'w') as article_file:
        article_file.write(
            f'This is a test article, written by [me]#{link_id}.\n\n'
            f'This is the second paragraph. This is a [link]#{article_id} to this article self.\n\n'
        )
