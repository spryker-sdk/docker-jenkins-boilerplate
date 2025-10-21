import jenkins.model.Jenkins
import hudson.model.User
import java.nio.file.*
import java.nio.charset.StandardCharsets
import groovy.transform.Field

// ===== env / dynamic path =====
@Field final String REGION    = System.getenv('AWS_REGION') ?: 'unknown-region'
@Field final String PROJECT   = System.getenv('SPRYKER_PROJECT_NAME') ?: 'unknown-project'
@Field final List<String> SSM_PARAMS = [
  "/${PROJECT}/base_task_definition/SPRYKER_SCHEDULER_PASSWORD",
  "/${PROJECT}/codebuild/base_task_definition/SPRYKER_SCHEDULER_PASSWORD",
]

// Jenkins user to mint token for
@Field final String USERNAME = System.getenv('SPRYKER_SCHEDULER_USER') ?: 'svc-spryker'
@Field final String LABEL    = "bootstrap-" + System.currentTimeMillis()

// Jenkins home as a Path
// [CHANGED] Always use the *actual running* Jenkins root dir; no env/default fallback.
@Field final Path HOME     = Jenkins.get().getRootDir().toPath()                                // [CHANGED]
@Field final Path OUT_DIR  = HOME.resolve('secrets').resolve('bootstrap')
@Field final Path OUT_FILE = OUT_DIR.resolve("${USERNAME}.token")
Files.createDirectories(OUT_DIR)

// ---- marker to signal completion to entrypoint ----
@Field final Path MARKER_DIR  = OUT_DIR
@Field final Path MARKER_FILE = MARKER_DIR.resolve(".token_ready")

def writeMarker() {
  try {
    Files.createDirectories(this.@MARKER_DIR)
    Files.write(
      this.@MARKER_FILE,
      ("ready@" + new Date().toString() + "\n").getBytes("UTF-8"),
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
    )
    try {
      def perms = [
        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
      ] as Set
      Files.setPosixFilePermissions(this.@MARKER_FILE, perms)
    } catch (Throwable ignore) {}
    println "[bootstrap] wrote marker: ${this.@MARKER_FILE}"
  } catch (Throwable t) {
    println "[bootstrap] WARNING: failed to write marker ${this.@MARKER_FILE}: ${t.message}"
  }
}

// ---- resolve ApiTokenProperty class safely (handles package differences)
Class apiTokenPropertyClass
try {
  apiTokenPropertyClass = Class.forName('jenkins.security.apitoken.ApiTokenProperty')
} catch (Throwable ignore) {
  apiTokenPropertyClass = Class.forName('jenkins.security.ApiTokenProperty') // older cores
}

// ===== helpers =====
def sh(Map<String,String> env, String cmd) {
  def pb = new ProcessBuilder(["bash","-lc", cmd]).redirectErrorStream(true)
  if (env) pb.environment().putAll(env)
  def p = pb.start()
  String out = p.inputStream.getText(StandardCharsets.UTF_8.name()).trim()
  int rc = p.waitFor()
  [rc: rc, out: out]
}

def writeTokenFile(Path outFile, String uuid, String value) {
  String content = (uuid ? "tokenUuid=${uuid}\n" : "") + "tokenValue=${value}\n"
  Files.write(outFile, content.getBytes('UTF-8'),
    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
  try {
    def perms = [
      java.nio.file.attribute.PosixFilePermission.OWNER_READ,
      java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
    ] as Set
    Files.setPosixFilePermissions(outFile, perms)
  } catch (Throwable ignore) {}
}

// --- DEBUG HELPERS (logs only; no behavior change) ---
def rightN(String s, int n) {
  if (s == null) return ""
  int len = s.length()
  int start = Math.max(0, len - n)
  return s.substring(start, len)
}

def mask(String s, int head=6, int tail=4) {
  if (!s) return "(null)"
  if (s.length() <= head + tail) return s
  return s.take(head) + "…" + rightN(s, tail)
}

def dbgToken(String where, String uuid, String value) {
  println "[debug] token@${where}: len=${value?.size() ?: 0}, masked=${mask(value)}, uuid=${uuid ?: '(none)'}"
  try {
    def basic = "${USERNAME}:${value ?: ''}".bytes.encodeBase64().toString()
    println "[debug] basicHeaderSample: Authorization: Basic " + mask(basic, 8, 6)
  } catch (Throwable ignore) {}
}

// [NEW] Validate token against the running Jenkins before reuse
def isTokenValid(String user, String token) {                                                      // [NEW]
  if (!token) return false                                                                         // [NEW]
  try {                                                                                            // [NEW]
    String base = System.getenv('JENKINS_URL') ?: (Jenkins.get().getRootUrl() ?: 'http://127.0.0.1:8080/')
    base = base.replaceAll('/+$','')
    def cmd = """curl -s -o /dev/null -w '%{http_code}' \
      -H "Authorization: Basic ${("${user}:${token}").bytes.encodeBase64().toString()}" \
      "${base}/whoAmI/api/json" """
    def r = sh([:], cmd)
    return r.rc == 0 && r.out == '200'
  } catch (Throwable t) {
    return false
  }
}                                                                                                  // [NEW]

// publish to all target params
def publishToSSM(String value) {
  if (!value) return
  final String region  = this.@REGION
  final String project = this.@PROJECT
  if (!region || project == 'unknown-project') {
    println "[bootstrap] Skipping SSM (REGION/PROJECT not set): REGION=${region} PROJECT=${project}"
    return
  }
  this.@SSM_PARAMS.each { String param ->
    def r = sh([TOKEN_VALUE: value, AWS_REGION: region],
      'aws ssm put-parameter --region "$AWS_REGION" --name "'+param+'" --type SecureString --overwrite --value "$TOKEN_VALUE"')
    println "[bootstrap] SSM put-parameter rc=${r.rc} -> ${param}"
    if (r.rc != 0) {
      println "[bootstrap] WARNING: aws cli returned non-zero; output:\n${r.out}"
    }
  }
}

// ===== main flow =====
String tokenValue
String tokenUuid = null

// If local file already exists, validate and reuse if still good; otherwise re-mint
if (Files.exists(OUT_FILE)) {
  def text = Files.readString(OUT_FILE, StandardCharsets.UTF_8)
  def m  = (text =~ /(?m)^tokenValue=(.+)$/)
  def mu = (text =~ /(?m)^tokenUuid=(.+)$/)
  if (m.find()) {
    tokenValue = m.group(1).trim()
    tokenUuid  = (mu.find() ? mu.group(1).trim() : null)
    dbgToken("disk", tokenUuid, tokenValue)

    // [NEW] validate-before-reuse (auto self-heal if secrets rotated)
    if (isTokenValid(USERNAME, tokenValue)) {                                                      // [NEW]
      println "[bootstrap] token file exists and is valid; skipping generation"                    // [NEW]
      publishToSSM(tokenValue)                                                                     // [NEW]
      writeMarker()                                                                                // [NEW]
      return                                                                                       // [NEW]
    } else {                                                                                       // [NEW]
      println "[bootstrap] existing token is NOT valid; minting a new one"                         // [NEW]
    }
  } else {
    println "[bootstrap] token file exists but tokenValue not found; will generate a new token"
  }
}

// Create or load user
def u = User.getById(USERNAME, true)

// Ensure the ApiTokenProperty exists
def p = u.getProperty(apiTokenPropertyClass as Class)
if (p == null) {
  p = apiTokenPropertyClass.getDeclaredConstructor().newInstance()
  u.addProperty(p)
  u.save()
}

// ===== generate & extract token (getter-or-field tolerant) =====
def tokenStore = p.getClass().getMethod('getTokenStore').invoke(p)
def t = tokenStore.getClass().getMethod('generateNewToken', String).invoke(tokenStore, LABEL)

// Helper: try getter, Groovy property, then direct field
def readProp = { obj, List<String> names ->
  for (String n : names) {
    try { def m = obj.getClass().getMethod(n); def v = m.invoke(obj); if (v != null) return v.toString() } catch (Throwable ignore) {}
    try { def v = obj."$n"; if (v != null) return v.toString() } catch (Throwable ignore) {}
    try {
      def fName = n.startsWith('get') ? n.substring(3,4).toLowerCase() + n.substring(4) : n
      def f = obj.getClass().getDeclaredField(fName); f.setAccessible(true)
      def v = f.get(obj); if (v != null) return v.toString()
    } catch (Throwable ignore) {}
  }
  return null
}

tokenValue = readProp(t, ['getPlainValue','plainValue','getValue','value'])
tokenUuid  = readProp(t, ['getTokenUuid','tokenUuid','getUuid','uuid'])

if (!tokenValue) {
  try {
    def legacy = p.getClass().getMethod('getApiToken').invoke(p)
    if (legacy) tokenValue = legacy.toString()
  } catch (Throwable ignore) {}
}

dbgToken("generated", tokenUuid, tokenValue)

// [NEW] Ensure token store changes are flushed to disk
try { u.save() } catch (Throwable ignore) {}                                                       // [NEW]

// Write local file and update SSM
writeTokenFile(OUT_FILE, tokenUuid, tokenValue)
println "[bootstrap] generated API token for ${USERNAME}; wrote ${OUT_FILE}"
println "[bootstrap] updating SSM params: ${this.@SSM_PARAMS.join(', ')}"
publishToSSM(tokenValue)

// mark completion so entrypoint can proceed
writeMarker()
