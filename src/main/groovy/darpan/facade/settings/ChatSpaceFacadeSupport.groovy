package darpan.facade.settings

import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.notification.TenantNotificationSupport

/**
 * Shaping and validation for the tenant chat-space facade, shared by list#TenantChatSpaces and
 * save#TenantChatSpace so the two can never disagree about what a chat space looks like on the wire.
 *
 * Extracted when Slack support turned a five-field projection into a provider-aware one with legacy
 * mirrors; the projection had been duplicated as two long inline expressions, and the response shape
 * is now something the UI's generated types are pinned to.
 */
class ChatSpaceFacadeSupport {

    /** Provider ids the product can actually deliver to — see TenantNotificationSupport. */
    static final List<String> SUPPORTED_PROVIDERS = [
            TenantNotificationSupport.PROVIDER_GOOGLE,
            TenantNotificationSupport.PROVIDER_SLACK,
    ].asImmutable()

    /**
     * Rejects a provider the delivery path cannot serve, and — separately — a NON-DEFAULT provider
     * whose Enumeration row is missing.
     *
     * <p>The catalog check exists because of how the 1.5.0 sharing release failed: a new
     * enumeration-FK column shipped ahead of its seed rows, and every save died on a raw FK violation
     * that named a constraint rather than the missing data. Here that situation reports the actual
     * remedy instead of a stack trace.</p>
     *
     * <p>Google Chat is deliberately exempt. It is the implicit default for every space that existed
     * before Slack support, and saving one is behaviour that predates the catalog entirely — gating
     * it on a data load would mean an un-upgraded deployment could no longer edit the chat spaces it
     * already has. That is a regression the new feature has no right to cause, so a Google space
     * saves either way and simply persists a null provider (see {@link #resolvePersistableProvider}).
     * Slack has no such history: it cannot work without the catalog, so it fails loudly here.</p>
     *
     * @return an operator-facing message, or null when the provider is usable
     */
    static String validateChatProvider(def ec, Object rawProvider) {
        String provider = TenantNotificationSupport.resolveChatProvider(rawProvider)
        if (!SUPPORTED_PROVIDERS.contains(provider)) {
            return "Unsupported chat provider '${provider}'. Choose Google Chat or Slack.".toString()
        }
        if (provider == TenantNotificationSupport.PROVIDER_GOOGLE) return null
        if (findProviderEnum(ec, provider) == null) {
            return ("The chat provider catalog is not loaded on this deployment, so '${provider}' " +
                    "cannot be saved. Load this release's upgrade data (DarpanChatProvider enumerations) " +
                    "and try again.").toString()
        }
        return null
    }

    /**
     * The value to write to TenantChatSpace.chatProviderEnumId — the resolved provider when its
     * Enumeration row exists, otherwise null.
     *
     * <p>Null is not a loss of information: TenantNotificationSupport.resolveChatProvider reads null
     * as Google Chat, which is the only provider that can reach this branch (validateChatProvider
     * already rejected every other one whose catalog row is missing). Writing the id blindly would
     * hit the TCSPACE_PROVIDER foreign key on a deployment whose upgrade data has not loaded, which
     * is precisely the failure this whole path exists to avoid.</p>
     */
    static String resolvePersistableProvider(def ec, Object rawProvider) {
        String provider = TenantNotificationSupport.resolveChatProvider(rawProvider)
        return findProviderEnum(ec, provider) == null ? null : provider
    }

    private static def findProviderEnum(def ec, String provider) {
        return TenantScopedFinder.findGlobalUnscoped(ec, "moqui.basic.Enumeration",
                        "framework reference data: chat provider catalog lookup")
                ?.condition("enumId", provider)
                ?.useCache(true)?.one()
    }

    /**
     * The wire shape of one chat space.
     *
     * <p>googleChatConfigured / googleChatWebhookUrl are the pre-Slack names, kept one release and
     * deliberately mirroring the RESOLVED provider-agnostic values rather than the legacy column. A
     * browser still running an older UI bundle reads only those two: mirroring makes it show a Slack
     * space as configured-but-mislabelled, where reading the legacy column would show every Slack
     * space as "Not configured" and invite an admin to "fix" it by pasting the URL again.</p>
     */
    static Map<String, Object> toChatSpaceMap(def ec, def spaceRow, boolean inUse) {
        if (spaceRow == null) return null
        String provider = TenantNotificationSupport.resolveChatProvider(spaceRow.chatProviderEnumId)
        String resolvedWebhookUrl = TenantNotificationSupport.resolveWebhookUrl(spaceRow)
        boolean configured = resolvedWebhookUrl ? true : false

        String slackInstallId = ((spaceRow.slackInstallId)?.toString()?.trim())
        String slackChannelId = ((spaceRow.slackChannelId)?.toString()?.trim())
        // A Slack space delivering through a workspace install needs no webhook to count as
        // configured — the token and channel id ARE its configuration.
        boolean botConfigured = provider == TenantNotificationSupport.PROVIDER_SLACK &&
                slackInstallId && slackChannelId
        configured = configured || botConfigured
        return [
                chatSpaceId         : spaceRow.chatSpaceId,
                spaceName           : spaceRow.spaceName,
                chatProviderEnumId  : provider,
                chatProviderLabel   : providerLabel(ec, provider),
                slackInstallId      : slackInstallId,
                slackChannelId      : slackChannelId,
                slackChannelName    : ((spaceRow.slackChannelName)?.toString()?.trim()),
                deliveryMode        : botConfigured ? TenantNotificationSupport.DELIVERY_SLACK_BOT :
                        TenantNotificationSupport.DELIVERY_WEBHOOK,
                webhookConfigured   : configured,
                webhookUrl          : resolvedWebhookUrl,
                googleChatConfigured: configured,
                googleChatWebhookUrl: resolvedWebhookUrl,
                isActive            : ((spaceRow.isActive)?.toString()?.trim()) ?: "Y",
                inUse               : inUse,
                createdByUserId     : spaceRow.createdByUserId,
                createdDate         : spaceRow.createdDate,
                lastUpdatedDate     : spaceRow.lastUpdatedDate,
        ]
    }

    /**
     * Validates the Slack bot coordinates on a save, and confirms the named workspace really belongs
     * to the acting tenant.
     *
     * <p>The install check is not redundant with the facade's own tenant scoping: {@code
     * slackInstallId} arrives from the client, and without verifying ownership a tenant could point
     * one of its chat spaces at another tenant's workspace token and post into their Slack.</p>
     *
     * @return an operator-facing message, or null when the coordinates are usable
     */
    static String validateSlackDestination(def ec, String tenantId, Object rawInstallId, Object rawChannelId) {
        String installId = ((rawInstallId)?.toString()?.trim())
        String channelId = ((rawChannelId)?.toString()?.trim())
        if (!installId) return null
        if (!channelId) return "Choose a Slack channel for this space."
        def installRow = TenantScopedFinder.findOneOwnedByTenant(ec,
                "darpan.reconciliation.SlackWorkspaceInstall", "slackInstallId", installId, tenantId,
                "Slack install verified against the active tenant after a primary-key read")
        if (installRow == null) return "That Slack workspace is not connected for this tenant."
        if (((installRow.isActive)?.toString()?.trim()) == "N") {
            return "That Slack workspace has been disconnected. Reconnect it before using it."
        }
        return null
    }

    /**
     * Display name for a provider. Falls back to a built-in label rather than the raw enum id so the
     * settings list stays readable on a deployment whose enumeration rows have not loaded yet — the
     * same gap validateChatProvider reports on write, but a read must not be blocked by it.
     */
    static String providerLabel(def ec, Object rawProvider) {
        String provider = TenantNotificationSupport.resolveChatProvider(rawProvider)
        String fallback = provider == TenantNotificationSupport.PROVIDER_SLACK ? "Slack" :
                (provider == TenantNotificationSupport.PROVIDER_GOOGLE ? "Google Chat" : provider)
        try {
            def enumRow = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.basic.Enumeration",
                            "framework reference data: chat provider label lookup")
                    ?.condition("enumId", provider)
                    ?.useCache(true)?.one()
            return ((enumRow?.description)?.toString()?.trim()) ?: fallback
        } catch (Throwable ignored) {
            return fallback
        }
    }
}
