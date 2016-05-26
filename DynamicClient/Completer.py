import os
import re
import readline

commands = ['HELP', 'INSTALL', 'START', 'INFO', 'TRIGGER', 'EXECUTE', 'CLOSE', 'KILL']
commandDescriptions = [
    ('HELP', 'Print description of commands'),
    ('INSTALL <APK file>', 'Install APK file onto device'),
    ('START <app dir>', 'Load info for app (should contain appInfo.json)'),
    ('TRIGGER <path #>', 'Inject events to trigger path in appInfo.json'),
    ('CLOSE', 'Close connection to IntelliDroidService and exit')
]

RE_SPACE = re.compile('.*\s+$', re.M)

class Completer:
    def __init__(self):
        pass

    def _listdir(self, root):
        "List directory 'root' appending the path separator to subdirs."
        res = []
        for name in os.listdir(root):
            path = os.path.join(root, name)
            if os.path.isdir(path):
                name += os.sep
                # Only list directories (skip files)
            res.append(name)
        return res

    def _complete_path(self, path, state):
        "Perform completion of filesystem path."
        if not path:
            return self._listdir('./')

        expandedPath = os.path.expanduser(path)
        dirname, rest = os.path.split(expandedPath)
        tmp = dirname if dirname else '.'
        res = [os.path.join(dirname, p) for p in self._listdir(tmp) if p.startswith(rest)]
        # more than one match, or single match which does not exist (typo)
        if not res:
            return None

        if len(res) > 1:
            return res[state]
        # resolved to a single directory, so return list of files below it
        #if os.path.isdir(path):
        #    return [os.path.join(path, p) for p in self._listdir(path)]
        # exact file match terminates this completion
        try :
            return res[state]
        except IndexError:
            return None

    def complete_extra(self, text, state):
        "Completions for the 'extra' command."
        if not text:
            return self._complete_path('./', state)

        return self._complete_path(text, state)

    def complete(self, text, state):
        # Show all commands if nothing entered
        buffer = readline.get_line_buffer()
        line = readline.get_line_buffer().split()
        if not line:
            return [c + ' ' for c in commands][state]

        # Resolve command or path
        options = [x for x in commands if x.startswith(text)]
        if not options or not text:
            return self.complete_extra(text, state)
        else:
            try:
                return options[state] + ' '
            except IndexError:
                return None

