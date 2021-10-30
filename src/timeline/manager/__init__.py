import flask
import json
import os

app = flask.Flask('Timeline Manager')

@app.route('/')
def index_demo():
    return 'Main page'

def serve(db_path):
    os.environ['FLASK_ENV'] = 'development'
    app.config.from_mapping(DB_PATH=db_path)
    app.run(port=1102, debug=True)
    print()

def init(db_path):
    db_path.mkdir()
    with open(db_path / 'meta.json', 'w') as meta:
        json.dump({}, meta)
