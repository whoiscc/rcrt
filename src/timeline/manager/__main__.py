import sys
import pathlib

import manager


assert __name__ == '__main__'
cmd = len(sys.argv) > 1 and sys.argv[1] or 'help'
if cmd == 'help':
    print('This is help message.')
elif cmd == 'serve':
    manager.serve(pathlib.Path(sys.argv[2]))
elif cmd == 'init':
    manager.init(pathlib.Path(sys.argv[2]))
else:
    print('try "help" command')
    sys.exit(1)
