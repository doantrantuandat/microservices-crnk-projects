# verify.ps1 - end-to-end validation of crnk-framework-lts (io.github.doantrantuandat:*:4.0.0-lts.2) as a
# real external Maven Central consumer. Run after `docker compose up --build -d` and all 7 containers are
# healthy. See README.md for the full topology.

$ErrorActionPreference = 'Continue'
$Gateway  = 'http://localhost:28080'
$Accounts = 'http://localhost:28081'
$Catalog  = 'http://localhost:28082'
$Ordering = 'http://localhost:28083'

$script:PassCount = 0
$script:FailCount = 0

function Invoke-Api {
    param(
        [string]$Method = 'GET',
        [string]$Url,
        [string[]]$ExtraHeaders = @(),
        [string]$Body = $null,
        [string]$ContentType = 'application/vnd.api+json'
    )
    $curlArgs = @('-s', '-g', '-w', "`nHTTP_STATUS:%{http_code}", '-X', $Method)
    foreach ($h in $ExtraHeaders) { $curlArgs += @('-H', $h) }
    # Body goes through a temp file (-d @file), never as a literal command-line argument: Windows
    # PowerShell 5.1's native-argv marshalling corrupts JSON strings with many adjacent `"` characters
    # when passed directly (confirmed live - a literal '{"data":...}' arrives at curl.exe missing its
    # opening quote, e.g. '{data":...}', a guaranteed 400 "Json Parsing failed" on every POST). Routing
    # through a file sidesteps the native-argv quoting path entirely.
    $tempFile = $null
    if ($Body) {
        $tempFile = [System.IO.Path]::GetTempFileName()
        [System.IO.File]::WriteAllText($tempFile, $Body)
        $curlArgs += @('-H', "Content-Type: $ContentType", '-d', "@$tempFile")
    }
    $curlArgs += @($Url)
    $output = (& curl.exe @curlArgs) -join "`n"
    if ($tempFile) { Remove-Item $tempFile -ErrorAction SilentlyContinue }
    if ($output -match '(?s)^(.*)\r?\nHTTP_STATUS:(\d+)\s*$') {
        return [PSCustomObject]@{ Status = [int]$Matches[2]; Body = $Matches[1].Trim() }
    }
    return [PSCustomObject]@{ Status = 0; Body = $output }
}

function Assert-Status {
    param([string]$Name, [int]$Expected, [PSCustomObject]$Result)
    if ($Result.Status -eq $Expected) {
        Write-Host "[PASS] $Name (HTTP $($Result.Status))" -ForegroundColor Green
        $script:PassCount++
    } else {
        Write-Host "[FAIL] $Name (expected HTTP $Expected, got $($Result.Status))" -ForegroundColor Red
        Write-Host "       Body: $($Result.Body)" -ForegroundColor Red
        $script:FailCount++
    }
    return $Result
}

function Assert-Contains {
    param([string]$Name, [string]$Needle, [PSCustomObject]$Result)
    if ($Result.Body -like "*$Needle*") {
        Write-Host "[PASS] $Name (found '$Needle')" -ForegroundColor Green
        $script:PassCount++
    } else {
        Write-Host "[FAIL] $Name (expected to find '$Needle')" -ForegroundColor Red
        Write-Host "       Body: $($Result.Body)" -ForegroundColor Red
        $script:FailCount++
    }
}

Write-Host "`n=== 1. Per-service CRUD sanity ===" -ForegroundColor Cyan
$r = Invoke-Api -Url "$Accounts/account/1"
Assert-Status "GET accounts-service seeded account 1" 200 $r | Out-Null
Assert-Contains "account 1 is Alice Nguyen" "Alice Nguyen" $r

# accounts-service's Account entity has no @GeneratedValue on its @Id (confirmed live, and consistent
# with Task 1's own already-passing test suite, which always supplies an explicit id too) - crnk-data-jpa
# requires the client to assign one. A random id (not 1-3, the seeded rows) avoids collisions on repeat runs.
$newAccountId = Get-Random -Minimum 100000 -Maximum 999999
$r = Invoke-Api -Method POST -Url "$Accounts/account" -ExtraHeaders @('X-Mock-Role: admin') `
    -Body "{`"data`":{`"type`":`"account`",`"id`":`"$newAccountId`",`"attributes`":{`"name`":`"Dave Le`",`"email`":`"dave@example.com`",`"plan`":`"free`"}}}"
Assert-Status "POST account as admin" 201 $r | Out-Null

$r = Invoke-Api -Url "$Catalog/product/1"
Assert-Status "GET catalog-service seeded product 1" 200 $r | Out-Null
Assert-Contains "product 1 is SKU-001" "SKU-001" $r

$r = Invoke-Api -Url "$Ordering/order/1"
Assert-Status "GET ordering-service seeded order 1" 200 $r | Out-Null
Assert-Contains "order 1 is ORD-1001" "ORD-1001" $r

Write-Host "`n=== 2. Forward adjacent-generation relationships ===" -ForegroundColor Cyan
$r = Invoke-Api -Url "$Catalog/product/1?include=owner"
Assert-Status "catalog(Boot3) -> accounts(Boot2): product/1?include=owner" 200 $r | Out-Null
Assert-Contains "included owner matches account 1" "Alice Nguyen" $r

$r = Invoke-Api -Url "$Ordering/orderLine/1?include=product"
Assert-Status "ordering(Boot4) -> catalog(Boot3): orderLine/1?include=product" 200 $r | Out-Null
Assert-Contains "included product matches product 1" "SKU-001" $r

Write-Host "`n=== 3. Forward skip-generation relationship ===" -ForegroundColor Cyan
$r = Invoke-Api -Url "$Ordering/order/1?include=account"
Assert-Status "ordering(Boot4) -> accounts(Boot2), skipping Boot3 entirely: order/1?include=account" 200 $r | Out-Null
Assert-Contains "included account matches account 1" "Alice Nguyen" $r

Write-Host "`n=== 4. REVERSE skip-generation relationship (headline new coverage) ===" -ForegroundColor Cyan
$r = Invoke-Api -Url "$Accounts/account/1?include=orders"
Assert-Status "accounts(Boot2) -> ordering(Boot4), skipping Boot3, REVERSE direction: account/1?include=orders" 200 $r | Out-Null
Assert-Contains "included orders contains order 1" '"id":"1","type":"order"' $r

Write-Host "`n=== 5. Negative case: broken cross-service reference ===" -ForegroundColor Cyan
# ordering-service's Order entity also has no @GeneratedValue (same confirmed workspace-wide convention
# as Account above) - an explicit client id is required or this seed POST 500s before ever reaching the
# negative case this section means to test.
$brokenOrderId = Get-Random -Minimum 100000 -Maximum 999999
$seedResp = Invoke-Api -Method POST -Url "$Ordering/order" -ExtraHeaders @('X-Mock-Role: admin') `
    -Body "{`"data`":{`"type`":`"order`",`"id`":`"$brokenOrderId`",`"attributes`":{`"orderNumber`":`"ORD-BROKEN`"},`"relationships`":{`"account`":{`"data`":{`"type`":`"account`",`"id`":`"99999`"}}}}}"
if ($seedResp.Status -eq 201 -and $seedResp.Body -match '"id":"(\d+)"') {
    $brokenId = $Matches[1]
    $r = Invoke-Api -Url "$Ordering/order/$brokenId`?include=account"
    Assert-Status "broken accountId=99999 -> clean 404, not 500" 404 $r | Out-Null
} else {
    Write-Host "[SKIP] negative case: could not seed a broken order (status $($seedResp.Status))" -ForegroundColor Yellow
}

Write-Host "`n=== 6. Validation failure (422) ===" -ForegroundColor Cyan
$r = Invoke-Api -Method POST -Url "$Accounts/account" -ExtraHeaders @('X-Mock-Role: admin') `
    -Body '{"data":{"type":"account","attributes":{"name":"","email":"not-an-email","plan":"x"}}}'
Assert-Status "blank name + invalid email -> 422" 422 $r | Out-Null

Write-Host "`n=== 7. MockAuth-denied (403) ===" -ForegroundColor Cyan
$r = Invoke-Api -Method POST -Url "$Catalog/product" `
    -Body '{"data":{"type":"product","attributes":{"sku":"X","name":"X","price":1.0}}}'
Assert-Status "POST product with no X-Mock-Role -> 403" 403 $r | Out-Null

Write-Host "`n=== 8. Gateway unauthenticated (401) ===" -ForegroundColor Cyan
$r = Invoke-Api -Method POST -Url "$Gateway/api/orders" -ContentType 'application/json' `
    -Body '{"accountId":1,"lines":[{"productId":1,"qty":1}]}'
Assert-Status "POST /api/orders with no credentials -> 401" 401 $r | Out-Null

Write-Host "`n=== 9. Invalid filter path (400, not 500 or data leak) ===" -ForegroundColor Cyan
$r = Invoke-Api -Url "$Ordering/order?filter[accountId]=1"
Assert-Status "filter[accountId] (raw relation-id, not traversed) -> 400 UNKNOWN_PARAMETER" 400 $r | Out-Null
$r2 = Invoke-Api -Url "$Ordering/order?filter[account.id]=1"
Assert-Status "filter[account.id] (correctly traversed) -> 200 (regression check)" 200 $r2 | Out-Null

Write-Host "`n=== 10. Content-type / Accept header check ===" -ForegroundColor Cyan
foreach ($pair in @(@{Name='accounts';Url="$Accounts/account"}, @{Name='catalog';Url="$Catalog/product"}, @{Name='ordering';Url="$Ordering/order"})) {
    # 'NUL' (a literal string, not $null): PowerShell drops a bare $null argument to a native exe
    # entirely instead of passing an empty string, which shifts every argument after it and silently
    # corrupts the call (confirmed live - curl.exe then misreads "-w" as -o's filename and the real URL
    # never gets sent). 'NUL' is Windows' own null-device filename, a normal literal string argument.
    $ctOut = & curl.exe -s -g -H "Accept: application/vnd.api+json" -D - -o 'NUL' $pair.Url
    if ($ctOut -match 'Content-Type:\s*application/vnd\.api\+json') {
        Write-Host "[PASS] $($pair.Name) honors Accept: application/vnd.api+json" -ForegroundColor Green
        $script:PassCount++
    } else {
        Write-Host "[FAIL] $($pair.Name) did not echo the JSON:API content type" -ForegroundColor Red
        Write-Host "       Headers: $ctOut" -ForegroundColor Red
        $script:FailCount++
    }
}

Write-Host "`n=== 11. Gateway orchestration endpoints ===" -ForegroundColor Cyan
$r = Invoke-Api -Url "$Gateway/api/orders/1/summary"
Assert-Status "GET /api/orders/1/summary" 200 $r | Out-Null
Assert-Contains "summary includes order number" "ORD-1001" $r
Assert-Contains "summary includes account name" "Alice Nguyen" $r
Assert-Contains "summary includes product sku" "SKU-001" $r

$r = Invoke-Api -Url "$Gateway/api/raw/accounts/1"
Assert-Status "GET /api/raw/accounts/1 (plain RestClient, no crnk-client)" 200 $r | Out-Null
Assert-Contains "raw endpoint shows Alice Nguyen" "Alice Nguyen" $r

$r = Invoke-Api -Url "$Gateway/api/health"
Assert-Status "GET /api/health (all backends up)" 200 $r | Out-Null
Assert-Contains "health reports accounts up" '"accounts":"UP"' $r
Assert-Contains "health reports catalog up" '"catalog":"UP"' $r
Assert-Contains "health reports ordering up" '"ordering":"UP"' $r

# Settles Task 4's one flagged open concern: does "create order, attach just-created lines by id" actually
# work end-to-end against a live ordering-service? Never exercised live before this. Credentials confirmed
# from the actual committed SecurityConfig.java (admin / demo-password-not-for-real-use).
$createBody = '{"accountId":1,"lines":[{"productId":1,"qty":2}]}'
$createBodyFile = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($createBodyFile, $createBody)
$createOut = & curl.exe -s -g -w "`nHTTP_STATUS:%{http_code}" -X POST "$Gateway/api/orders" `
    -u "admin:demo-password-not-for-real-use" -H "Content-Type: application/json" -d "@$createBodyFile"
Remove-Item $createBodyFile -ErrorAction SilentlyContinue
$createResult = if ($createOut -join "`n" -match '(?s)^(.*)\r?\nHTTP_STATUS:(\d+)\s*$') {
    [PSCustomObject]@{ Status = [int]$Matches[2]; Body = $Matches[1].Trim() }
} else { [PSCustomObject]@{ Status = 0; Body = ($createOut -join "`n") } }
Assert-Status "POST /api/orders with valid admin credentials -> 201" 201 $createResult | Out-Null
Assert-Contains "created order's account resolved correctly" "Alice Nguyen" $createResult
Assert-Contains "created order's line product resolved correctly" "SKU-001" $createResult
if ($createResult.Body -match '"orderId":(\d+)') {
    $newOrderId = $Matches[1]
    Start-Sleep -Seconds 1
    $refetch = Invoke-Api -Url "$Ordering/order/$newOrderId`?include=lines"
    Assert-Status "re-fetched new order $newOrderId directly from ordering-service" 200 $refetch | Out-Null
    Assert-Contains "re-fetched order's lines relationship is populated (not empty)" '"type":"orderLine"' $refetch
} else {
    Write-Host "[FAIL] could not parse orderId from create-order response to re-verify linkage" -ForegroundColor Red
    $script:FailCount++
}

Write-Host "`n=== 12. Resilience: stop catalog-service (optional-detail degradation) ===" -ForegroundColor Cyan
docker compose stop catalog-service | Out-Null
Start-Sleep -Seconds 3
$r = Invoke-Api -Url "$Gateway/api/orders/1/summary"
Assert-Status "summary still 200 with catalog down (product detail degrades, account is unaffected)" 200 $r | Out-Null
$r2 = Invoke-Api -Url "$Gateway/api/health"
Assert-Contains "health reports catalog down" '"catalog":"UNREACHABLE"' $r2
if ($r2.Body -notmatch '"catalog":"UP"') {
    $healthAlt = Invoke-Api -Url "$Gateway/api/health"
    if ($healthAlt.Body -match '"catalog":"(DOWN|UNREACHABLE)"') {
        Write-Host "[PASS] catalog correctly reported non-UP while stopped" -ForegroundColor Green
        $script:PassCount++
    }
}
docker compose start catalog-service | Out-Null
Start-Sleep -Seconds 5

Write-Host "`n=== 13. Resilience: stop accounts-service (hard-dependency + fail-open wrapper) ===" -ForegroundColor Cyan
docker compose stop accounts-service | Out-Null
Start-Sleep -Seconds 3
$r = Invoke-Api -Url "$Catalog/product/1?include=owner"
Assert-Status "catalog's fail-open wrapper: clean 404 (not a hang/500) with accounts-service down" 404 $r | Out-Null
$r2 = Invoke-Api -Url "$Gateway/api/orders/1/summary"
if ($r2.Status -eq 502 -or $r2.Status -eq 200) {
    Write-Host "[PASS] gateway summary endpoint did not hang/crash with accounts-service down (got HTTP $($r2.Status))" -ForegroundColor Green
    $script:PassCount++
} else {
    Write-Host "[FAIL] gateway summary endpoint returned unexpected status $($r2.Status) with accounts-service down" -ForegroundColor Red
    $script:FailCount++
}
docker compose start accounts-service | Out-Null
Start-Sleep -Seconds 5

Write-Host "`n=== 14. Concurrency smoke test ===" -ForegroundColor Cyan
$jobs = 1..20 | ForEach-Object {
    # 'NUL' not $null - see the note in section 10 above (same PowerShell native-argv pitfall).
    Start-Job -ScriptBlock { param($u) (& curl.exe -s -o 'NUL' -w "%{http_code}" $u) } -ArgumentList "$Ordering/order/1?include=account"
}
$results = $jobs | Wait-Job | Receive-Job
$jobs | Remove-Job
$bad = $results | Where-Object { $_ -ne '200' }
if (-not $bad) {
    Write-Host "[PASS] 20/20 concurrent requests returned 200" -ForegroundColor Green
    $script:PassCount++
} else {
    Write-Host "[FAIL] $($bad.Count) of 20 concurrent requests did not return 200: $($bad -join ',')" -ForegroundColor Red
    $script:FailCount++
}

Write-Host "`n=== SUMMARY: $script:PassCount passed, $script:FailCount failed ===" -ForegroundColor $(if ($script:FailCount -eq 0) { 'Green' } else { 'Red' })
if ($script:FailCount -gt 0) { exit 1 }
