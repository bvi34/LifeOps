# espeak-ng data (English subset)

Pronunciation data for the on-device neural voice: a Piper model is fed phonemes, and espeak-ng
inside the runtime turns text into them by reading these files.

**This is a subset.** The complete data set is 18 MB across 355 files; these 13 files (848 KB) are
the parts an English voice actually reads — the phoneme tables, intonation data, the English
dictionary, and the English voice definitions (`lang/gmw/en*`, which cover en-US, en-GB and its
variants). The subset was arrived at by removing files until synthesis broke, then putting the last
one back, and verified by synthesizing with both a US and a GB voice.

A voice in another language will not speak until its dictionary and `lang/` file are added here.
That is why `VoiceCatalog` is English-only.

Source: <https://github.com/espeak-ng/espeak-ng>, via the model repositories at
`huggingface.co/csukuangfj/vits-piper-*`.

**Licence: GPLv3**, as part of espeak-ng.
