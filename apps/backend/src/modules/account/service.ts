import type { Database } from "../../infra/db/database.js"
import type { PushDispatchService } from "../../infra/push/dispatchService.js"
import type { PairSessionRecord } from "../../contracts/dbRecords.js"
import type { CanvasSyncHub } from "../canvasSync/hub.js"
import type { ProfilePhotoStorage } from "../profile/service.js"
import { requireSessionContext } from "../_shared/session.js"
import { clearPartnerMetadata, getProfileFrom } from "../_shared/profiles.js"
import { getPairSession } from "../_shared/pairs.js"

interface DeletedAccount {
  pairSession: PairSessionRecord | null
  partnerUserId: string | null
  displayName: string
  photoPaths: string[]
}

export class AccountService {
  constructor(
    private readonly db: Database,
    private readonly pushDispatchService?: PushDispatchService,
    private readonly profilePhotoStorage?: ProfilePhotoStorage,
    private readonly canvasSyncHub?: Pick<CanvasSyncHub, "closePairSession">,
  ) {}

  /**
   * Permanently deletes the signed-in user. Unpairs them first, the same way as a disconnect, so
   * the partner keeps their own account but loses the shared canvas, streak and pairing details.
   * Everything else the user owns goes with the users row through ON DELETE CASCADE.
   */
  async deleteAccount(accessToken: string): Promise<void> {
    const context = await requireSessionContext(this.db, accessToken)
    const userId = context.user.id

    const deleted = await this.db.transaction(async (tx): Promise<DeletedAccount> => {
      const pairSession = await getPairSession(tx, userId)
      const partnerUserId = pairSession
        ? pairSession.user_one_id === userId ? pairSession.user_two_id : pairSession.user_one_id
        : null
      const profile = await getProfileFrom(tx, userId)
      const partnerProfile = partnerUserId ? await getProfileFrom(tx, partnerUserId) : null
      const photoPaths = [
        profile?.profile_photo_path,
        profile?.partner_profile_photo_path,
        partnerProfile?.partner_profile_photo_path,
      ].filter((path): path is string => Boolean(path))

      if (pairSession && partnerUserId) {
        await tx.query(`DELETE FROM pair_sessions WHERE id = $1`, [pairSession.id])
        await clearPartnerMetadata(tx, [partnerUserId])
      }
      await tx.query(`DELETE FROM users WHERE id = $1`, [userId])

      return {
        pairSession,
        partnerUserId,
        displayName: profile?.display_name ?? "Your partner",
        photoPaths: await this.unreferencedPhotoPaths(tx, photoPaths),
      }
    })

    if (deleted.pairSession && deleted.partnerUserId) {
      this.canvasSyncHub?.closePairSession(deleted.pairSession.id)
      this.pushDispatchService?.enqueuePairingDisconnected(
        deleted.pairSession.id,
        deleted.partnerUserId,
        userId,
        deleted.displayName,
      )
    }
    await this.removeProfilePhotos(userId, deleted.photoPaths)
  }

  /** Photo files are shared between partners' rows, so only files no one points at any more can go. */
  private async unreferencedPhotoPaths(db: Pick<Database, "query">, paths: string[]): Promise<string[]> {
    const candidates = [...new Set(paths)]
    if (candidates.length === 0) return []
    const placeholders = candidates.map((_, index) => `$${index + 1}`).join(", ")
    const rows = await db.query<{ path: string }>(
      `
      SELECT profile_photo_path AS path FROM user_profiles WHERE profile_photo_path IN (${placeholders})
      UNION
      SELECT partner_profile_photo_path AS path FROM user_profiles WHERE partner_profile_photo_path IN (${placeholders})
      `,
      candidates,
    )
    const referenced = new Set(rows.rows.map((row) => row.path))
    return candidates.filter((path) => !referenced.has(path))
  }

  private async removeProfilePhotos(userId: string, paths: string[]): Promise<void> {
    if (!this.profilePhotoStorage || paths.length === 0) return
    try {
      await this.profilePhotoStorage.remove(paths)
    } catch (error) {
      // The account is already gone; a leftover file is not worth failing the request over.
      console.error("[account] profile photo cleanup failed", {
        userId,
        paths,
        error: error instanceof Error ? error.message : String(error),
      })
    }
  }
}
