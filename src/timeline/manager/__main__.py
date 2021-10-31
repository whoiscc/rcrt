import sys
import pathlib

import manager

assert __name__ == '__main__'
db_path = pathlib.Path(sys.argv[1])
if not db_path.exists():
    manager.init(db_path)
manager.serve(db_path)
