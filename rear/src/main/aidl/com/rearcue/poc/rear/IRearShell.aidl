// RearCue shell channel: the UserService runs inside the Shizuku (shell uid) process,
// and the app process calls it over Binder to run commands. See docs/adr/0001 (Route A).
// (English comments on purpose: the AIDL toolchain mangles non-ASCII text.)
package com.rearcue.poc.rear;

interface IRearShell {
    /** Run one shell command; returns "exit=<code>\n<stdout+stderr>". */
    String run(String command);
}
