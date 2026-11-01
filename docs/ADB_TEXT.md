# Typing text from a computer

With *Settings → Advanced → Accept text from adb* turned on (it is off by default), a computer
connected to the phone over USB debugging can type text into the field you are editing:

```sh
adb shell am broadcast -a io.github.lewin671.localvoiceime.COMMIT_TEXT \
    --es base64 "$(printf '%s' '净化器6 PM2.5 高于 12' | base64)"
```

`adb shell input text` is no substitute: it sends ASCII key events, which the keyboard treats
like a physical keyboard's. Pinyin composes letters, and after Chinese text is committed, digits
select the predicted words the floating candidate bar offers (press Escape first to close it).

The keyboard must be the active input method and be shown for the field: tap the field first
if it is hidden. The text is committed in one piece, like a candidate: no key events, no
pinyin, whatever the language. Unfinished pinyin is dropped first.

| Extra | Meaning |
|---|---|
| `--es base64 <Base64>` | the text, UTF-8 encoded in Base64; survives the shell's quoting whatever the characters (line breaks in the Base64 are ignored) |
| `--es text <text>` | the text as is; `adb shell` hands the command line to the phone's shell, so quote it twice if it contains spaces: `--es text "'a b'"` |

Give exactly one of them. Debug builds listen for
`io.github.lewin671.localvoiceime.debug.COMMIT_TEXT`.

`am broadcast` prints the outcome:

| `result=` | `data=` |
|---|---|
| `-1` | `committed N characters` |
| `1` | the switch is off |
| `2` | why the text was refused (missing, not Base64-encoded UTF-8, empty, longer than 100,000 characters) |
| `3` | the keyboard is not shown for a text field |
| `0`, no data | nothing received it: the keyboard is not the active input method, or the installed version predates this feature |

Only a sender that holds `android.permission.DUMP` gets through: adb's shell has it, and an app
installed on the phone cannot get it. The text is never logged.
