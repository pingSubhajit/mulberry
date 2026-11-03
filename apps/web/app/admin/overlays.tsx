"use client"

import { MoreHorizontal, X, type LucideIcon } from "lucide-react"
import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from "react"

import { cn } from "@/lib/utils"

import { IconButton } from "./ui"

export type MenuAction = {
  label: string
  icon: LucideIcon
  onSelect: () => void
  tone?: "neutral" | "danger"
  disabled?: boolean
}

/** Shows an element while its `group` is hovered, keyboard-focused or has its menu open, and always on touch screens. */
export const REVEAL_IN_GROUP =
  "group-hover:opacity-100 group-has-[:focus-visible]:opacity-100 group-has-[[aria-expanded=true]]:opacity-100 [@media(hover:none)]:opacity-100"

/** The opposite of REVEAL_IN_GROUP, for whatever the revealed element replaces. */
export const HIDE_IN_GROUP =
  "group-hover:opacity-0 group-has-[:focus-visible]:opacity-0 group-has-[[aria-expanded=true]]:opacity-0 [@media(hover:none)]:opacity-0"

// Fixed sizes so the menu can be placed before it is shown: h-8 rows, p-1 and a 1px border.
const MENU_WIDTH = 176
const MENU_ROW_HEIGHT = 32
const MENU_CHROME = 10
const VIEWPORT_MARGIN = 8

/** A "more" button that opens a short list of actions. Lives in the top layer, so scroll areas never clip it. */
export function ActionMenu({
  label,
  actions,
  className
}: Readonly<{ label: string; actions: readonly MenuAction[]; className?: string }>) {
  const menuId = useId()
  const triggerRef = useRef<HTMLButtonElement | null>(null)
  const menuRef = useRef<HTMLDivElement | null>(null)
  const [isOpen, setIsOpen] = useState(false)
  const itemCount = actions.length

  useEffect(() => {
    const menu = menuRef.current
    if (!menu) return

    const place = (event: ToggleEvent) => {
      if (event.newState !== "open") return
      const trigger = triggerRef.current?.getBoundingClientRect()
      if (!trigger) return
      const height = itemCount * MENU_ROW_HEIGHT + MENU_CHROME
      const below = trigger.bottom + 4
      const top =
        below + height > window.innerHeight - VIEWPORT_MARGIN ? Math.max(VIEWPORT_MARGIN, trigger.top - 4 - height) : below
      const left = Math.min(
        Math.max(VIEWPORT_MARGIN, trigger.right - MENU_WIDTH),
        window.innerWidth - MENU_WIDTH - VIEWPORT_MARGIN
      )
      menu.style.top = `${top}px`
      menu.style.left = `${left}px`
    }

    const dismiss = () => menu.hidePopover()

    const sync = (event: ToggleEvent) => {
      const open = event.newState === "open"
      setIsOpen(open)
      if (open) {
        menu.querySelector<HTMLButtonElement>("[role=menuitem]:not(:disabled)")?.focus()
        window.addEventListener("scroll", dismiss, true)
        window.addEventListener("resize", dismiss)
      } else {
        window.removeEventListener("scroll", dismiss, true)
        window.removeEventListener("resize", dismiss)
      }
    }

    menu.addEventListener("beforetoggle", place)
    menu.addEventListener("toggle", sync)
    return () => {
      menu.removeEventListener("beforetoggle", place)
      menu.removeEventListener("toggle", sync)
      window.removeEventListener("scroll", dismiss, true)
      window.removeEventListener("resize", dismiss)
    }
  }, [itemCount])

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const items = Array.from(
      event.currentTarget.querySelectorAll<HTMLButtonElement>("[role=menuitem]:not(:disabled)")
    )
    if (items.length === 0) return
    const index = items.findIndex((item) => item === document.activeElement)
    const next: Record<string, number> = {
      ArrowDown: (index + 1) % items.length,
      ArrowUp: (index - 1 + items.length) % items.length,
      Home: 0,
      End: items.length - 1
    }
    const target = next[event.key]
    if (target === undefined) return
    event.preventDefault()
    items[target]?.focus()
  }

  return (
    <>
      <IconButton
        ref={triggerRef}
        aria-label={label}
        aria-haspopup="menu"
        aria-expanded={isOpen}
        aria-controls={menuId}
        popoverTarget={menuId}
        className={cn(isOpen && "bg-admin-fill text-admin-fg", className)}
      >
        <MoreHorizontal />
      </IconButton>
      <div
        ref={menuRef}
        id={menuId}
        popover="auto"
        role="menu"
        aria-label={label}
        onKeyDown={onKeyDown}
        style={{ width: MENU_WIDTH }}
        className={cn(
          "fixed inset-auto m-0 rounded-[10px] border border-admin-line-strong bg-admin-panel p-1 text-admin-fg",
          "shadow-[0_8px_24px_rgba(0,0,0,0.12),0_1px_2px_rgba(0,0,0,0.06)]"
        )}
      >
        {actions.map((action) => {
          const Icon = action.icon
          return (
            <button
              key={action.label}
              type="button"
              role="menuitem"
              disabled={action.disabled}
              onClick={() => {
                menuRef.current?.hidePopover()
                action.onSelect()
              }}
              className={cn(
                "flex h-8 w-full items-center gap-2.5 rounded-tag px-2 text-left text-[13px] outline-none",
                "transition-[background-color] duration-150 ease-admin hover:bg-admin-fill focus-visible:bg-admin-fill",
                "disabled:pointer-events-none disabled:opacity-40",
                action.tone === "danger" ? "text-admin-negative" : "text-admin-fg"
              )}
            >
              <Icon
                className={cn("size-4 shrink-0", action.tone === "danger" ? "text-admin-negative" : "text-admin-muted")}
                strokeWidth={1.5}
              />
              {action.label}
            </button>
          )
        })}
      </div>
    </>
  )
}

/** A modal panel that slides in from the right edge. */
export function Sheet({
  open,
  onClose,
  title,
  footer,
  children
}: Readonly<{ open: boolean; onClose: () => void; title: ReactNode; footer?: ReactNode; children: ReactNode }>) {
  const dialogRef = useRef<HTMLDialogElement | null>(null)
  const titleId = useId()

  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog) return
    if (open && !dialog.open) dialog.showModal()
    if (!open && dialog.open) dialog.close()
  }, [open])

  return (
    <dialog
      ref={dialogRef}
      aria-labelledby={titleId}
      onClose={onClose}
      onClick={(event) => {
        // Only the backdrop targets the dialog itself; the panel content fills it.
        if (event.target === event.currentTarget) onClose()
      }}
      className={cn(
        "fixed inset-y-0 right-0 left-auto m-0 h-dvh max-h-dvh w-full max-w-[440px] border-0 border-l border-admin-line bg-admin-panel p-0 text-admin-fg",
        "shadow-[-12px_0_32px_rgba(0,0,0,0.12)] outline-none",
        "translate-x-full transition-[translate,overlay,display] transition-discrete duration-300 ease-admin open:translate-x-0 starting:open:translate-x-full",
        "backdrop:bg-black/0 backdrop:transition-[background-color,overlay,display] backdrop:transition-discrete backdrop:duration-300",
        "open:backdrop:bg-black/40 starting:open:backdrop:bg-black/0",
        "motion-reduce:transition-none motion-reduce:backdrop:transition-none"
      )}
    >
      <div className="flex h-full flex-col">
        <header className="flex h-12 shrink-0 items-center gap-3 border-b border-admin-line pl-5 pr-2">
          <h2 id={titleId} className="min-w-0 flex-1 truncate text-sm font-medium text-admin-fg">
            {title}
          </h2>
          <IconButton aria-label="Close" onClick={onClose}>
            <X />
          </IconButton>
        </header>
        <div className="min-h-0 flex-1 overflow-y-auto">{children}</div>
        {footer ? (
          <footer className="flex shrink-0 items-center justify-between gap-3 border-t border-admin-line px-5 py-3">
            {footer}
          </footer>
        ) : null}
      </div>
    </dialog>
  )
}
