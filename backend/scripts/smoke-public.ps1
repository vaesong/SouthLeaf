param([string]$BaseUrl = 'http://127.0.0.1:8090', [string]$ExpectedMode = 'demo')
$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$script:checks = 0
function Check($condition, [string]$message) {
    if (-not $condition) { throw "FAILED: $message" }
    $script:checks++
}
function Read-Public([string]$path) {
    $r = Invoke-RestMethod -Uri ($BaseUrl + '/api/v1/public' + $path) -TimeoutSec 15
    Check ($r.code -eq 'OK') "Successful envelope $path"
    Check (-not [string]::IsNullOrWhiteSpace($r.requestId)) "Request ID $path"
    return $r
}
function Expect-Status([string]$path, [int]$expected, [hashtable]$headers = @{}, [string]$method = 'GET') {
    $curlArgs = @('--silent', '--show-error', '--max-time', '15', '--output', 'NUL', '--write-out', '%{http_code}')
    if ($method -eq 'HEAD') { $curlArgs += '--head' }
    foreach ($key in $headers.Keys) { $curlArgs += @('--header', ($key + ': ' + $headers[$key])) }
    $actual = [int](& curl.exe @curlArgs ($BaseUrl + '/api/v1/public' + $path))
    if ($LASTEXITCODE -ne 0) { throw "curl transport failure $path" }
    Check ($actual -eq $expected) "$method $path expected=$expected actual=$actual"
}
$homeResponse = Read-Public '/home'
Check ($homeResponse.meta.dataMode -eq $ExpectedMode) 'Expected data mode'
Check (@($homeResponse.data.businesses).Count -eq 3) 'Three curated home services'
Check (@($homeResponse.data.packages).Count -eq 0) 'No repeated homepage package section'
Check (@($homeResponse.data.brand.contact.channels).Count -eq 3) 'Three public demo social channels'
Check (@($homeResponse.data.featuredWorks).Count -gt 0) 'Visible demo works'
Check (@($homeResponse.data.heroItems).Count -gt 0) 'Visible hero references'
$brandResponse = Read-Public '/brand'
Check (@($brandResponse.data.team).Count -eq 2) 'Two demo team profiles'
$aboutMedia = @($brandResponse.data.team | ForEach-Object { $_.portraitMedia })
foreach ($channel in $brandResponse.data.contact.channels) {
    Check ($channel.qrCodeStatus -eq 'PLACEHOLDER') ('Explicit placeholder QR ' + $channel.id)
    $aboutMedia += $channel.qrCodeMedia
}
foreach ($media in $aboutMedia) {
    Check ($null -ne $media) 'Configured public About image'
    $null = Read-Public ('/media/' + $media.id + '/preview')
    $image = Invoke-WebRequest -UseBasicParsing -Uri ($BaseUrl + $media.previewUrl) -TimeoutSec 15
    Check ($image.Headers['Content-Type'] -like 'image/*') 'About image type'
    Check ($image.Headers['Cache-Control'] -eq 'no-store') 'About image no-store'
    Expect-Status ('/media/' + $media.id + '/preview/content') 200 @{} 'HEAD'
}
$businesses = Read-Public '/businesses'
Check (@($businesses.data.items).Count -eq 6) 'Complete service directory remains available'
foreach ($b in $businesses.data.items) {
    $null = Read-Public ('/businesses/' + $b.id)
    $packages = Read-Public ('/packages?businessId=' + $b.id)
    Check (@($packages.data.items).Count -gt 0) ('Demo package for ' + $b.id)
    foreach ($p in $packages.data.items) { $null = Read-Public ('/packages/' + $p.id) }
}
$ids = @()
$cursor = $null
$pages = 0
 do {
    $query = '/works?limit=2'
    if ($cursor) { $query += '&cursor=' + [uri]::EscapeDataString($cursor) }
    $r = Read-Public $query
    $ids += @($r.data.items | ForEach-Object { $_.id })
    $cursor = $r.meta.nextCursor
    $pages++
    Check ($pages -le 10) 'Bounded pagination'
} while ($cursor)
Check ($ids.Count -eq 6) 'All six demo works traversed'
Check (@($ids | Select-Object -Unique).Count -eq $ids.Count) 'No duplicate work IDs'
foreach ($id in $ids) {
    $w = Read-Public ('/works/' + $id)
    Check (@($w.data.media).Count -gt 0) ('Detail media ' + $id)
    foreach ($m in $w.data.media) {
        $null = Read-Public ('/media/' + $m.id + '/preview')
        $image = Invoke-WebRequest -UseBasicParsing -Uri ($BaseUrl + $m.previewUrl) -TimeoutSec 15
        Check ($image.Headers['Content-Type'] -like 'image/*') ('Image content type ' + $id)
        Check ($image.Headers['Cache-Control'] -eq 'no-store') ('Image no-store ' + $id)
        Check ($image.RawContentLength -gt 0) ('Nonempty image ' + $id)
        Expect-Status ('/media/' + $m.id + '/preview/content') 200 @{} 'HEAD'
    }
}
Expect-Status '/works/missing-smoke-test' 404
Expect-Status '/works?limit=-1' 400
Expect-Status '/media/work-child/original/content' 400
Expect-Status '/media/work-child/preview/content' 416 @{Range='bytes=0-10'}
Write-Output "PUBLIC_SMOKE_PASS checks=$script:checks works=$($ids.Count) mode=$ExpectedMode"
