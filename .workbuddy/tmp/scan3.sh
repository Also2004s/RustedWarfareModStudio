cd '/sdcard/rustedWarfare/units/人机的玩笑' || exit 1
echo '=== copyFrom block content (first 3)'
for f in $(grep -rlE 'copyFrom:[[:space:]]*"""' . | head -3); do
  echo "--- FILE $f"
  n=$(grep -nE 'copyFrom:[[:space:]]*"""' "$f" | head -1 | cut -d: -f1)
  sed -n "${n},$((n+6))p" "$f"
done
echo '=== ROOT lines inside ini (sample)'
grep -rnE '^[[:space:]]*ROOT:' . | head -8
echo '=== lines with comma after ROOT'
grep -rnE 'ROOT:[^,]*,' . | head -8
