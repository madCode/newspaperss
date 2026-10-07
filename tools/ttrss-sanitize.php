<?php
// Runs feed HTML through a tt-rss checkout's own Sanitizer, as its API does for getHeadlines with
// show_content, so the platform corpus can test what NewspapeRSS gets through tt-rss.
//
//   php tools/ttrss-sanitize.php /path/to/tt-rss SITE_URL < content.html > sanitized.html
//
// Only Sanitizer and UrlHelper are loaded; the rest of tt-rss is stubbed below.
[$_, $ttrss, $site] = $argv;

class Config { static function get_self_url(): string { return "https://ttrss.invalid/"; } }
class Prefs { const STRIP_IMAGES = "STRIP_IMAGES"; static function get(...$a) { return false; } }
class PluginHost {
    const HOOK_IFRAME_WHITELISTED = 1; const HOOK_SANITIZE = 2;
    static function getInstance(): PluginHost { return new PluginHost(); }
    function run_hooks_until($hook, $value, ...$args) { return false; }
    function chain_hooks_callback($hook, $callback, ...$args) {}
}
class RSSUtils {
    static function decode_srcset(string $srcset): array {
        preg_match_all('/(?:\A|,)\s*(?P<url>(?!,)\S+(?<!,))\s*(?P<size>\s\d+w|\s\d+(?:\.\d+)?(?:[eE][+-]?\d+)?x|)\s*(?=,|\Z)/', $srcset, $m, PREG_SET_ORDER);
        return array_map(fn($x) => ['url' => trim($x['url']), 'size' => trim($x['size'])], $m);
    }
    static function encode_srcset(array $m): string { return implode(',', array_map(fn($x) => trim($x['url']) . ' ' . trim($x['size']), $m)); }
}
function clean(mixed $p): mixed { return is_string($p) ? trim(strip_tags($p)) : $p; }
function with_trailing_slash(string $s): string { return str_ends_with($s, '/') ? $s : "$s/"; }

require "$ttrss/classes/UrlHelper.php";
require "$ttrss/classes/Sanitizer.php";

echo Sanitizer::sanitize(stream_get_contents(STDIN), false, null, $site);
