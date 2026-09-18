cd '/sdcard/rustedWarfare/units/人机的玩笑' || exit 1
echo '=== key | total | withComma'
grep -rhoE '^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*[[:space:]]*:' . 2>/dev/null \
  | sed -E 's/^[[:space:]]*//; s/[[:space:]]*:$//' \
  | sort | uniq -c | sort -rn > /sdcard/keytotal.txt
grep -rhE '^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*[[:space:]]*:.*,' . 2>/dev/null \
  | sed -E 's/^[[:space:]]*//; s/[[:space:]]*:.*//' \
  | sort | uniq -c | sort -rn > /sdcard/keycomma.txt
echo '--- top total'
head -60 /sdcard/keytotal.txt
echo '--- top comma'
head -60 /sdcard/keycomma.txt
echo '=== triple quote single line'
grep -rnE '""".*"""' . | head -10
echo '=== copyFrom triple blocks'
grep -rnE 'copyFrom:[[:space:]]*"""' . | head -5
