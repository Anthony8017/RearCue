# erratum -- verdict wording from the pre-final script (ticket #16)

Raw output here is untouched (archive rule). This round's `e12-class` line was produced by the
script version that still wrote a mechanism claim in the class parenthetical:

    e12-class : E12-IGNORED (left ON at +6s vs control +6s, within the 4s sampling tolerance: the wake key was ignored)

The final script reports facts only (verdict words, numbers and the conclusion are unchanged):

    e12-class : E12-IGNORED (left ON at +6s vs control +6s: unchanged within the 4s sampling tolerance)

"Whether the wake key was ignored" is a mechanism question and lives in
`docs/poc-findings.md` (ticket #16) with log support, not in the verdict text.
