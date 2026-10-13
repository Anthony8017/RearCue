' Scheduled-task host. wscript.exe is a GUI program, so this process has no
' console. It runs start-bridge.cmd hidden and waits: the task stays Running
' (IgnoreNew) and the retry loop still lives in that cmd.
Option Explicit
Dim shell, fso, here, launcher, code
Set shell = CreateObject("Wscript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
here = fso.GetParentFolderName(WScript.ScriptFullName)
launcher = fso.BuildPath(here, "start-bridge.cmd")
shell.CurrentDirectory = here
code = shell.Run("cmd.exe /d /s /c """ & launcher & """", 0, True)
WScript.Quit code
