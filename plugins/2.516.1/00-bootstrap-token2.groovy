import jenkins.model.Jenkins
import hudson.model.User
import java.nio.file.*

// ---- resolve ApiTokenProperty class safely (handles package differences)
Class apiTokenPropertyClass
try {
  apiTokenPropertyClass = Class.forName('jenkins.security.apitoken.ApiTokenProperty')
} catch (Throwable ignore) {
  apiTokenPropertyClass = Class.forName('jenkins.security.ApiTokenProperty') // older cores
}

// ---- config
def username = 'svc-spryker'
def label    = 'bootstrap-' + System.currentTimeMillis()

def home   = System.getenv('JENKINS_HOME') ?: Jenkins.instance.rootDir.absolutePath
def outDir = Paths.get(home, 'secrets', 'bootstrap')
Files.createDirectories(outDir)
def out    = outDir.resolve('svc-spryker.token')

// idempotent
if (Files.exists(out)) {
  println "[bootstrap] token file exists at ${out} — skipping"
  return
}

// create or load user
def u = User.getById(username, true)

// ensure the ApiTokenProperty exists
def p = u.getProperty(apiTokenPropertyClass as Class)
if (p == null) {
  p = apiTokenPropertyClass.getDeclaredConstructor().newInstance()
  u.addProperty(p)
  u.save()
}

// generate new token (uuid + plain value)
def tokenStore = p.getClass().getMethod('getTokenStore').invoke(p)
def t = tokenStore.getClass().getMethod('generateNewToken', String).invoke(tokenStore, label)

// write the token (plain value is visible only now)
def tokenUuid  = t.getClass().getMethod('getTokenUuid').invoke(t)
def tokenValue = t.getClass().getMethod('getPlainValue').invoke(t)
Files.write(out, "tokenUuid=${tokenUuid}\ntokenValue=${tokenValue}\n".getBytes('UTF-8'),
  StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

println "[bootstrap] wrote API token for ${username} to ${out}"
