Option Explicit
Dim shell, fso, folder, script, command
Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
folder = fso.GetParentFolderName(WScript.ScriptFullName)
script = fso.BuildPath(folder, "start-codex-shared.ps1")
command = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File """ & script & """"
shell.Run command, 0, False
