#!/usr/bin/env bash
# Builds the general dictionary the keyboard corrects against:
# utilities/src/main/assets/wordbook-en.txt
#
# WHERE THE WORDS COME FROM
#
# SCOWL — Spell Checker Oriented Word Lists, by Kevin Atkinson. It is the list behind the spell
# checkers in Aspell, Hunspell and most of the free desktop world, it is graded by how common a word
# is, and — the part that decided it — its licence permits use, modification, distribution and sale
# provided the copyright notice travels with it. That notice is written into the head of the
# generated file, which is the whole of what shipping this costs us.
#
# The grading is the reason for SCOWL over a bigger list. A dictionary that knows every word in
# English is a *worse* autocorrect than one that knows the common ones: every obscure word is
# another thing an ordinary typo can be dragged towards. SCOWL's size buckets are used directly as
# the three tiers in the generated file — 10 is roughly the four thousand words that make up most of
# what anybody writes, 20 the next few thousand, 35 the long tail that is still ordinary English.
# Nothing above 35 is shipped.
#
# WHAT IS THROWN AWAY, AND WHY
#
#  - anything with a capital in it. Proper names and acronyms are exactly what should *not* be
#    corrected towards, and the keyboard refuses to correct a capitalised word anyway — see
#    Corrections. Dropping them here is the same rule, applied where it costs nothing;
#  - abbreviations and the `upper` lists, for the same reason;
#  - anything that is not letters and an interior apostrophe. `don't` and `dog's` stay, because a
#    keyboard that does not know them corrects them into something else, which is worse than not
#    correcting at all.
#
# Re-run it when the dictionary should change; it is not part of the build. The generated file is
# committed, because a build that reaches the network is a build that breaks when somebody else's
# server does.
#
#   ./utilities/tools/make-wordbook.sh
set -euo pipefail

version="2020.12.07"
url="https://downloads.sourceforge.net/project/wordlist/SCOWL/${version}/scowl-${version}.tar.gz"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="${here}/../src/main/assets/wordbook-en.txt"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "Fetching SCOWL ${version}…"
curl -sSL -o "${work}/scowl.tar.gz" "$url"
tar xzf "${work}/scowl.tar.gz" -C "$work"
final="${work}/scowl-${version}/final"

# Byte order, not the machine's locale: the keyboard binary-searches this file by character code,
# and a list sorted by anybody's collation rules is a list it cannot find half of.
export LC_ALL=C

tier_of() { # $1 = SCOWL size bucket -> the tier written into the file
  case "$1" in
    10) echo 1 ;;
    20) echo 2 ;;
    *) echo 3 ;;
  esac
}

: >"${work}/tiered"
for size in 10 20 35; do
  tier="$(tier_of "$size")"
  for kind in words contractions; do
    for dialect in english american; do
      file="${final}/${dialect}-${kind}.${size}"
      [ -f "$file" ] || continue
      # Lowercase letters and an interior apostrophe, nothing else, nothing longer than a word.
      grep -E "^[a-z][a-z']*$" "$file" \
        | grep -vE "'$" \
        | awk -v tier="$tier" 'length($0) >= 1 && length($0) <= 20 { print tier "\t" $0 }' \
        >>"${work}/tiered"
    done
  done
done

# WORDS THE LIST PREDATES
#
# SCOWL is a general word list from 2020 and English has moved. `online`, `inbox`, `website`,
# `username`, `login`, `app`, `podcast`, `emoji`, `wifi`, `laptop`, `backup`, `screenshot` — all
# ordinary words somebody types on a phone every week, none of them in it. A keyboard that does not
# know a word does three unhelpful things with it: it will not suggest it, it may correct it into
# something else, and it may split it in two. So they are added, as tier 2, which is what they are.
#
# What belongs here: ordinary English the general list is simply too old to have. What does not:
# brand names. `facebook`, `google` and `iphone` are deliberately absent — a dictionary of English is
# not a directory of companies, and the keyboard protects them another way, by refusing to touch a
# capitalised word and by learning whatever you put back with a backspace.
cat >>"${work}/tiered" <<'SUPPLEMENT'
2	online
2	offline
2	inbox
2	inline
2	website
2	websites
2	username
2	usernames
2	filename
2	filenames
2	login
2	logins
2	logout
2	uploads
2	download
2	downloads
2	app
2	apps
2	email
2	emails
2	emailed
2	emailing
2	podcast
2	podcasts
2	emoji
2	emojis
2	selfie
2	selfies
2	hashtag
2	hashtags
2	smartphone
2	smartphones
2	wifi
2	laptop
2	laptops
2	browser
2	browsers
2	backup
2	backups
2	screenshot
2	screenshots
2	screenshotted
2	timestamp
2	timestamps
2	dataset
2	datasets
2	runtime
2	workflow
2	workflows
2	codebase
2	hostname
2	signup
2	dropdown
2	ebook
2	ebooks
2	wearable
2	wearables
2	livestream
2	livestreams
2	chatbot
2	chatbots
2	unfollow
2	paywall
2	smartwatch
2	touchscreen
2	voicemail
2	wildcard
2	workaround
2	workarounds
SUPPLEMENT

# A word in two buckets keeps the lowest tier — the commonest claim wins.
sort -t$'\t' -k2,2 -k1,1n "${work}/tiered" | awk -F'\t' '$2 != last { print $1 $2; last = $2 }' >"${work}/body"

words="$(wc -l <"${work}/body" | tr -d ' ')"

{
  cat <<HEADER
# The general dictionary: ${words} English words, each with how common it is —
# 1 for the few thousand that make up most of what anybody writes, 2 for the next
# few thousand, 3 for the ordinary rest. One word per line, the tier first, sorted
# by character code so the keyboard can binary-search it without reading it all in.
#
# Generated by utilities/tools/make-wordbook.sh from SCOWL ${version}. It is a list
# of English words and nothing else: it is shipped with the app, it is never added
# to, and it says nothing about anybody. What the keyboard learns from what *you*
# type is the separate, readable, deletable list — see LexiconStore.
#
# Copyright 2000-2020 by Kevin Atkinson
#
# Permission to use, copy, modify, distribute and sell these word lists, the
# associated scripts, the output created from the scripts, and its documentation
# for any purpose is hereby granted without fee, provided that the above copyright
# notice appears in all copies and that both that copyright notice and this
# permission notice appear in supporting documentation. Kevin Atkinson makes no
# representations about the suitability of this array for any purpose. It is
# provided "as is" without express or implied warranty.
HEADER
  cat "${work}/body"
} >"$out"

echo "Wrote ${words} words to ${out}"
