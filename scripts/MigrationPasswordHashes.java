import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.security.crypto.bcrypt.BCrypt;

// Generate unreachable initial passwords; administrators can reset each reader's password.
// Compile/run with the application's pinned spring-security-crypto dependency.
class MigrationPasswordHashes {
    public static void main(String[] args) {
        int count = Integer.parseInt(args[0]);
        if (count < 1 || count > 10000) throw new IllegalArgumentException("Invalid count");
        SecureRandom random = new SecureRandom();
        System.out.println("[");
        for (int i = 0; i < count; i++) {
            byte[] password = new byte[32];
            random.nextBytes(password);
            String hash = BCrypt.hashpw(Base64.getEncoder().encodeToString(password), BCrypt.gensalt(12));
            System.out.println("  \"" + hash + "\"" + (i + 1 < count ? "," : ""));
            java.util.Arrays.fill(password, (byte) 0);
        }
        System.out.println("]");
    }
}
