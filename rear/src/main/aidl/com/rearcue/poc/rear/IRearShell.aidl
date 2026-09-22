// RearCue shell channel: the UserService runs inside the Shizuku (shell uid) process,
// and the app process calls it over Binder to run commands. See docs/adr/0001 (Route A).
// (English comments on purpose: the AIDL toolchain mangles non-ASCII text.)
package com.rearcue.poc.rear;

interface IRearShell {
    /** Reserved destroy method defined by the Shizuku server (transaction 16777115). */
    void destroy() = 16777114;

    /** Run one shell command; returns "exit=<code>\n<stdout+stderr>". */
    String run(String command) = 1;
}
