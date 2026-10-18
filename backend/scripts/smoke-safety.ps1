param([string]$BaseUrl = 'http://127.0.0.1:8090')
$ErrorActionPreference = 'Stop'
$checks = 0
function Assert-NotFound([string]$Path, [string]$Method = 'GET') {
    $status = 0
    try { $response = Invoke-WebRequest -UseBasicParsing -Uri ($BaseUrl + $Path) -Method $Method; $status = [int]$response.StatusCode }
    catch { if ($null -eq $_.Exception.Response) { throw }; $status = [int]$_.Exception.Response.StatusCode }
    if ($status -ne 404) { throw "Expected disabled route $Method $Path to return 404; actual $status" }
    $script:checks++
}
foreach ($path in @('/api/categories/getAllCategories', '/api/packages/getAllPackages', '/api/swipper/getSwipperImages', '/public-data/catalog.json', '/application.properties')) {
    Assert-NotFound $path
}
Assert-NotFound '/api/categories/add' 'OPTIONS'
Assert-NotFound '/api/packages/add' 'OPTIONS'
$homeResponse = Invoke-RestMethod -Uri ($BaseUrl + '/api/v1/public/home')
if ($homeResponse.meta.dataMode -ne 'demo') { throw 'This local safety smoke expects demo data' }
$checks++
Write-Output "SAFETY_SMOKE_PASS checks=$checks mode=demo (GET/OPTIONS only; no business writes)"
