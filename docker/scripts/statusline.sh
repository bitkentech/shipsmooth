#!/usr/bin/env bash
input=$(cat)

# 1. Basic Info
model=$(echo "$input" | jq -r '.model.display_name')

# 2. Context Logic
ctx=$(echo "$input" | jq -r '.context_window.used_percentage // 0')
ctx_display="${ctx}%"

# Calculate total tokens from the current_usage object
tokens=$(echo "$input" | jq -r '.context_window.current_usage | [.[]] | add // 0')

if (( tokens >= 1000000 )); then
    tokens_display=$(printf "%.1fM" "$(echo "scale=2; $tokens / 1000000" | bc)")
elif (( tokens >= 1000 )); then
    tokens_display=$(printf "%.1fk" "$(echo "scale=1; $tokens / 1000" | bc)")
else
    tokens_display="$tokens"
fi

# 3. Colors for Context
if (( ctx >= 80 )); then ctx_color="\033[31m"
elif (( ctx >= 60 )); then ctx_color="\033[33m"
else ctx_color=""; fi
ctx_reset="${ctx_color:+\033[0m}"

# 4. Five Hour (Pro) Limits
pro_raw=$(echo "$input" | jq -r '.rate_limits.five_hour.used_percentage // 0')
# Round to 2 decimal places (or 0 if you prefer whole numbers)
pro_display=$(printf "%.2f%%" "$pro_raw")
ts=$(echo "$input" | jq -r '.rate_limits.five_hour.resets_at // empty')
resets_display="--"
if [[ -n "$ts" ]]; then
    resets_display="till $(date -d "@$ts" +"%I:%M %p")"
fi

# 5. Seven Day (Weekly) Limits
weekly_used=$(echo "$input" | jq -r '.rate_limits.seven_day.used_percentage // 0')
weekly_usage_rem=$(echo "100 - $weekly_used" | bc -l)
# Formatted to .2f as requested
weekly_usage_rem_display=$(printf "%.2f%%" "$weekly_usage_rem")

weekly_ts=$(echo "$input" | jq -r '.rate_limits.seven_day.resets_at // empty')
if [[ -n "$weekly_ts" ]]; then
    now=$(date +%s)
    secs_left=$(( weekly_ts - now ))
    if (( secs_left > 0 )); then
        weekly_time_pct=$(echo "scale=2; $secs_left * 100 / 604800" | bc)
        weekly_time_rem_display="$(printf "%.2f" $weekly_time_pct)%"
    else
        weekly_time_rem_display="0.00%"
    fi
else
    weekly_time_rem_display="--%"
fi

# 6. Final Output (Updated Format)
echo -e "$model | ${ctx_color}ctx: $ctx_display ($tokens_display)${ctx_reset} | pro: ${pro_display}% ($resets_display) | wk: $weekly_time_rem_display / $weekly_usage_rem_display (time rem / usage rem)"
