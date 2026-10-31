export const ReactionTypes = ["HEART", "HUG", "KISS", "SMILE", "LAUGH", "SPARKLE"] as const
export type ReactionType = (typeof ReactionTypes)[number]
