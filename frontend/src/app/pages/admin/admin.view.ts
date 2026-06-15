import { Component, OnInit, OnDestroy, ChangeDetectorRef, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { Subscription } from 'rxjs';
import { AuthService, UserDto } from '@app/core/services/auth.service';

@Component({
    selector: 'app-admin',
    templateUrl: './admin.view.html',
    styleUrls: ['./admin.view.scss'],
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [CommonModule, FormsModule],
})
export class AdminView implements OnInit, OnDestroy {
    users: UserDto[] = [];
    isLoading = false;
    loadError: string | null = null;

    editingRoles: Record<string, string> = {};
    roleError: string | null = null;
    roleSuccess: string | null = null;

    resetForms: Record<string, string> = {};   // email -> new password being typed
    showReset: Record<string, boolean> = {};    // email -> form visible
    resetError: string | null = null;
    resetSuccess: string | null = null;

    syncStatus: Record<string, 'idle' | 'running' | 'done' | 'error'> = {};
    skippedRepos: string[] = [];

    private subscription = new Subscription();

    get currentEmail(): string | null {
        return this.auth.email;
    }

    constructor(
        private auth: AuthService,
        private router: Router,
        private cdr: ChangeDetectorRef,
        private http: HttpClient,
    ) {}

    ngOnInit(): void {
        if (this.auth.role !== 'ADMIN') {
            void this.router.navigate(['/company-card']);
            return;
        }
        this.loadUsers();
    }

    ngOnDestroy(): void {
        this.subscription.unsubscribe();
    }

    loadUsers(): void {
        this.isLoading = true;
        this.loadError = null;
        this.subscription.add(
            this.auth.getUsers().subscribe({
                next: users => {
                    this.users = users;
                    this.editingRoles = {};
                    users.forEach(u => (this.editingRoles[u.email] = u.role));
                    this.isLoading = false;
                    this.cdr.markForCheck();
                },
                error: err => {
                    this.loadError = err?.status === 403
                        ? 'Access denied. Please log in again.'
                        : 'Failed to load users. Is the backend running?';
                    this.isLoading = false;
                    this.cdr.markForCheck();
                },
            })
        );
    }

    saveRole(user: UserDto): void {
        const newRole = this.editingRoles[user.email];
        if (!newRole || newRole === user.role) return;
        this.roleError = null;
        this.roleSuccess = null;
        this.subscription.add(
            this.auth.updateUserRole(user.email, newRole).subscribe({
                next: () => {
                    this.roleSuccess = `Role updated for "${user.email}".`;
                    this.cdr.markForCheck();
                    this.loadUsers();
                },
                error: err => {
                    this.roleError = err?.error?.error || 'Failed to update role.';
                    this.cdr.markForCheck();
                },
            })
        );
    }

    toggleReset(email: string): void {
        this.showReset[email] = !this.showReset[email];
        this.resetForms[email] = '';
        this.resetError = null;
        this.resetSuccess = null;
    }

    saveReset(email: string): void {
        const password = this.resetForms[email] || '';
        if (password.length < 6) {
            this.resetError = 'Password must be at least 6 characters.';
            return;
        }
        this.resetError = null;
        this.resetSuccess = null;
        this.subscription.add(
            this.auth.resetPassword(email, password).subscribe({
                next: () => {
                    this.resetSuccess = `Password reset for "${email}".`;
                    this.showReset[email] = false;
                    this.resetForms[email] = '';
                    this.cdr.markForCheck();
                },
                error: err => {
                    this.resetError = err?.error?.error || 'Failed to reset password.';
                    this.cdr.markForCheck();
                },
            })
        );
    }

    deleteUser(email: string): void {
        if (!confirm(`Delete user "${email}"? This cannot be undone.`)) return;
        this.subscription.add(
            this.auth.deleteUser(email).subscribe({
                next: () => this.loadUsers(),
                error: () => alert('Failed to delete user.'),
            })
        );
    }

    triggerSync(action: string): void {
        this.syncStatus[action] = 'running';
        this.cdr.markForCheck();
        this.subscription.add(
            this.http.post(`api/admin/${action}`, {}).subscribe({
                next: () => {
                    this.syncStatus[action] = 'done';
                    this.cdr.markForCheck();
                    if (action === 'github/sync/stats' || action === 'github/sync' || action === 'sync/all') {
                        // Poll for skipped repos after a short delay to allow the async job to finish
                        setTimeout(() => this.loadSkippedRepos(), 3000);
                    }
                },
                error: () => {
                    this.syncStatus[action] = 'error';
                    this.cdr.markForCheck();
                },
            })
        );
    }

    loadSkippedRepos(): void {
        this.http.get<{ repos: string[] }>('api/admin/github/sync/stats/skipped').subscribe({
            next: res => {
                this.skippedRepos = res.repos ?? [];
                this.cdr.markForCheck();
            },
        });
    }

    back(): void {
        void this.router.navigate(['/company-card']);
    }
}
