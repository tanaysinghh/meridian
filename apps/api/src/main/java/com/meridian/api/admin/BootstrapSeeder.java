package com.meridian.api.admin;

import com.meridian.api.orgs.Org;
import com.meridian.api.orgs.OrgRepository;
import com.meridian.api.orgs.OrgSettings;
import com.meridian.api.orgs.OrgSettingsRepository;
import com.meridian.api.users.Role;
import com.meridian.api.users.User;
import com.meridian.api.users.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Creates the first org and its admin user.
 *
 * <p>Successor to the bootstrap half of the old {@code npm run db:seed}. It runs only when asked —
 * pass {@code --seed} on the command line or set {@code MERIDIAN_SEED=true} — so an ordinary boot
 * never touches user data. It is idempotent: re-running updates the admin's password and name
 * rather than failing or creating a duplicate.
 *
 * <p>The guards from the original script are kept, because this is the one code path that creates
 * a credential:
 * <ul>
 *   <li>In production, {@code ADMIN_EMAIL} and {@code ADMIN_PASSWORD} are required — it refuses to
 *       invent a default administrator.</li>
 *   <li>{@code ADMIN_PASSWORD} must be at least 12 characters, in every environment.</li>
 * </ul>
 *
 * <p>Passing {@code --seed-demo} additionally loads the sample Acme dataset via
 * {@link DemoDataSeeder}. That refuses to run under the prod profile unless
 * {@code FORCE_DEMO_SEED=yes} is set, mirroring the old script's guard — demo rows in a real
 * tenant's database are hard to tell from real ones after the fact.
 */
@Component
public class BootstrapSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapSeeder.class);

    private static final int MIN_PASSWORD_LENGTH = 12;

    private final OrgRepository orgs;
    private final OrgSettingsRepository orgSettings;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Environment env;
    private final DemoDataSeeder demoDataSeeder;

    public BootstrapSeeder(OrgRepository orgs,
                           OrgSettingsRepository orgSettings,
                           UserRepository users,
                           PasswordEncoder passwordEncoder,
                           Environment env,
                           DemoDataSeeder demoDataSeeder) {
        this.orgs = orgs;
        this.orgSettings = orgSettings;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.env = env;
        this.demoDataSeeder = demoDataSeeder;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean wantDemo = args.containsOption("seed-demo")
                || Boolean.parseBoolean(env.getProperty("MERIDIAN_SEED_DEMO", "false"));
        boolean requested = wantDemo
                || args.containsOption("seed")
                || Boolean.parseBoolean(env.getProperty("MERIDIAN_SEED", "false"));
        if (!requested) {
            return;
        }
        Org org = seed();
        if (wantDemo) {
            seedDemo(org);
        }
    }

    /**
     * Loads the sample dataset into the bootstrapped org.
     *
     * @throws IllegalStateException in production unless {@code FORCE_DEMO_SEED=yes}
     */
    private void seedDemo(Org org) {
        boolean prod = Arrays.asList(env.getActiveProfiles()).contains("prod");
        if (prod && !"yes".equalsIgnoreCase(env.getProperty("FORCE_DEMO_SEED", ""))) {
            throw new IllegalStateException(
                    "[seed] refusing to load demo data in production; set FORCE_DEMO_SEED=yes to override");
        }
        if (org == null) {
            throw new IllegalStateException(
                    "[seed] --seed-demo needs a bootstrapped org; set ADMIN_EMAIL and ADMIN_PASSWORD");
        }
        demoDataSeeder.seed(org);
    }

    @Transactional
    public Org seed() {
        boolean prod = Arrays.asList(env.getActiveProfiles()).contains("prod");

        String adminEmail = Optional.ofNullable(env.getProperty("ADMIN_EMAIL"))
                .map(e -> e.trim().toLowerCase(Locale.ROOT))
                .orElse("");
        String adminPassword = Optional.ofNullable(env.getProperty("ADMIN_PASSWORD")).orElse("");

        if (adminEmail.isBlank() || adminPassword.isBlank()) {
            if (prod) {
                throw new IllegalStateException(
                        "[seed] ADMIN_EMAIL and ADMIN_PASSWORD are required in production");
            }
            log.warn("[seed] ADMIN_EMAIL / ADMIN_PASSWORD not set — skipping admin bootstrap");
            return null;
        }
        if (adminPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "[seed] ADMIN_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }

        String orgName = env.getProperty("ORG_NAME", "Meridian");
        String orgSlug = env.getProperty("ORG_SLUG", "meridian");
        String adminName = env.getProperty("ADMIN_NAME", "Admin");
        String githubLogin = env.getProperty("ADMIN_GITHUB_LOGIN");

        Org org = orgs.findBySlug(orgSlug)
                .map(existing -> {
                    existing.setName(orgName);
                    return existing;
                })
                .orElseGet(() -> new Org(orgName, orgSlug));
        org = orgs.save(org);

        if (orgSettings.findById(org.getId()).isEmpty()) {
            orgSettings.save(new OrgSettings(org.getId()));
        }

        final Org target = org;
        User admin = users.findByEmail(adminEmail)
                .orElseGet(() -> new User(target.getId(), adminEmail, Role.ADMIN));
        admin.setName(adminName);
        admin.setRole(Role.ADMIN);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        if (githubLogin != null && !githubLogin.isBlank()) {
            admin.setGithubLogin(githubLogin);
        }
        if (admin.getOrgId() == null) {
            admin.setOrgId(target.getId());
        }
        users.save(admin);

        // The password is never logged, only the fact that it was set.
        log.info("[seed] admin bootstrapped: {} (org: {})", adminEmail, orgSlug);
        return target;
    }
}
